// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring MVC 对话入口；模型与数据库由配置层装配。
 */
@SpringBootApplication
public class SpringBootAgentExample {
    /**
     * Spring 自身配置由 application.yml 装配，Agent 配置由数据库表读取。
     */
    public static void main(String[] args) {
        SpringApplication.run(SpringBootAgentExample.class, args);
    }
}
