// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.serialization.CanonicalPlanJson;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.plan.application.service.PlanCommittedPublisher;
import io.github.kongweiguang.ja.plan.application.service.PlanEventRegistry;
import io.github.kongweiguang.ja.plan.application.service.PlanService;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 使用真实临时 SQLite 验证 Plan 内部事件不破坏公开事件分页。 */
final class PlanEventPaginationTest extends PersistenceTestSupport {
    private static final String PLAN_ID = "plan_event_page";
    private static final String THREAD_ID = "thr_event_page";

    /** 内部事件页必须推进原始序号，后续交错的公开事件只能出现一次。 */
    @Test
    void advancesAcrossInternalPagesWithoutLosingPublicEvents() throws Exception {
        try (TestDatabase database = database("plan-event-pages")) {
            MybatisPlanRepository repository = repository(database.sessions(), database.mapper());
            seedOwner(database.sessions());
            repository.createPlan(new PlanRepository.CreatePlan(
                    PLAN_ID, THREAD_ID, "验证事件分页", 0, "create-plan", START));
            insertEvent(database.sessions(), "evt_internal_1", "plan_turn_settled", "internal-1");
            insertEvent(database.sessions(), "evt_internal_2", "plan_turn_activity_settled", "internal-2");
            insertEvent(database.sessions(), "evt_internal_3", "plan_turn_settled", "internal-3");
            insertEvent(database.sessions(), "evt_public_1", "plan_paused", "public-1");
            insertEvent(database.sessions(), "evt_internal_4", "plan_turn_activity_settled", "internal-4");
            insertEvent(database.sessions(), "evt_public_2", "plan_resumed", "public-2");
            insertEvent(database.sessions(), "evt_public_3", "plan_stopped", "public-3");

            PlanService service = service(repository);
            PlanModels.Page<PlanModels.PublicEvent> internalPage = service.readPlanEvents(PLAN_ID, "pev~1", 2);
            assertEquals(List.of(), internalPage.items());
            assertEquals("pev~4", internalPage.nextCursor());
            assertEquals(8, internalPage.eventSequence());

            PlanModels.Page<PlanModels.PublicEvent> mixedPage = service.readPlanEvents(
                    PLAN_ID, internalPage.nextCursor(), 2);
            assertEquals(List.of(5L, 7L), mixedPage.items().stream()
                    .map(PlanModels.PublicEvent::eventSequence).toList());
            assertEquals("pev~7", mixedPage.nextCursor());

            PlanModels.Page<PlanModels.PublicEvent> finalPage = service.readPlanEvents(
                    PLAN_ID, mixedPage.nextCursor(), 2);
            assertEquals(List.of(8L), finalPage.items().stream()
                    .map(PlanModels.PublicEvent::eventSequence).toList());
            assertNull(finalPage.nextCursor());
        }
    }

    /** 未知活动不得借内部事件过滤绕过公开事件闭集。 */
    @Test
    void rejectsUnknownEventActivity() throws Exception {
        try (TestDatabase database = database("plan-event-unknown")) {
            MybatisPlanRepository repository = repository(database.sessions(), database.mapper());
            seedOwner(database.sessions());
            repository.createPlan(new PlanRepository.CreatePlan(
                    PLAN_ID, THREAD_ID, "验证未知事件", 0, "create-plan", START));
            insertEvent(database.sessions(), "evt_unknown", "unexpected_activity", "unknown");

            PlanService service = service(repository);
            assertThrows(IllegalStateException.class, () -> service.readPlanEvents(PLAN_ID, null, 10));
        }
    }

    /** database() 已注册完整 Mapper 集；此 helper 仅替换事务 owner，保留真实 Plan SQL 与 SQLite 提交，避免重复注册冲突。 */
    private static MybatisPlanRepository repository(SqlSessionFactory sessions, ObjectMapper mapper) {
        return new MybatisPlanRepository(sessions, mapper, new PlanUnitOfWork.SessionOwner() {
            /** 单个 SqlSession 覆盖仓储操作；回滚异常只作诊断，不能遮蔽工作单元首因。 */
            @Override
            public <T> T execute(SqlSessionFactory factory, PlanUnitOfWork.Work<T> work) {
                try (SqlSession session = factory.openSession()) {
                    try {
                        T result = work.apply(new PlanUnitOfWork.Mappers(session.getMapper(PlanMapper.class)));
                        session.commit();
                        return result;
                    } catch (Throwable failure) {
                        try {
                            session.rollback();
                        } catch (Throwable rollbackFailure) {
                            if (failure != rollbackFailure) {
                                failure.addSuppressed(rollbackFailure);
                            }
                        }
                        if (failure instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        if (failure instanceof Error error) {
                            throw error;
                        }
                        throw new IllegalStateException("Plan test transaction failed", failure);
                    }
                }
            }
        });
    }

    /** 创建只读事件测试用 service，空观察注册表避免测试触发额外发布查询。 */
    private static PlanService service(MybatisPlanRepository repository) {
        PlanEventRegistry events = new PlanEventRegistry();
        return new PlanService(repository, new CanonicalPlanJson(new ObjectMapper()), Clock.fixed(START, java.time.ZoneOffset.UTC),
                1, null, events, new PlanCommittedPublisher(repository, events));
    }

    /** 为真实 createPlan 提供符合生产外键和 owner CAS 的隔离 Thread。 */
    private static void seedOwner(SqlSessionFactory sessions) throws Exception {
        try (SqlSession session = sessions.openSession();
             PreparedStatement workspace = session.getConnection().prepareStatement("""
                     INSERT INTO ja_workspace(workspace_id,root_path,kind,display_name,trust,revision,created_at_ms,updated_at_ms)
                     VALUES(?,?,?,?,?,0,?,?)
                     """);
             PreparedStatement thread = session.getConnection().prepareStatement("""
                     INSERT INTO ja_conversation_thread(thread_id,owner_user_id,workspace_id,title,revision,created_at_ms,updated_at_ms,
                         provider_id,model_id,access_mode,collaboration_mode,title_source)
                     VALUES(?,'user_local',?,?,0,?,?,?,?,'APPROVAL_REQUIRED','PLAN','MANUAL')
                     """)) {
            workspace.setString(1, "ws_event_page");
            workspace.setString(2, "C:/plan-event-page");
            workspace.setString(3, "PROJECT");
            workspace.setString(4, "事件分页测试");
            workspace.setString(5, "TRUSTED");
            workspace.setLong(6, START.toEpochMilli());
            workspace.setLong(7, START.toEpochMilli());
            workspace.executeUpdate();
            thread.setString(1, THREAD_ID);
            thread.setString(2, "ws_event_page");
            thread.setString(3, "事件分页测试");
            thread.setLong(4, START.toEpochMilli());
            thread.setLong(5, START.toEpochMilli());
            thread.setString(6, "provider_test");
            thread.setString(7, "model_test");
            thread.executeUpdate();
            session.commit();
        }
    }

    /** 用绑定参数追加特定持久事件，覆盖内部事件共用 Plan 序列的真实数据库行为。 */
    private static void insertEvent(SqlSessionFactory sessions, String eventId,
                                    String activity, String operationKey) throws Exception {
        try (SqlSession session = sessions.openSession();
             PreparedStatement event = session.getConnection().prepareStatement("""
                     INSERT INTO ja_plan_event(event_id,plan_id,plan_revision,activity,operation_key,created_at_ms)
                     VALUES(?,?,0,?,?,?)
                     """)) {
            event.setString(1, eventId);
            event.setString(2, PLAN_ID);
            event.setString(3, activity);
            event.setString(4, operationKey);
            event.setLong(5, START.toEpochMilli());
            event.executeUpdate();
            session.commit();
        }
    }
}



