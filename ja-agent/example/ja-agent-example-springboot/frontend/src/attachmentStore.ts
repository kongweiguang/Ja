// @author kongweiguang

const databaseName = "ja-spring-workbench-attachments";
const objectStoreName = "pending-uploads";
let database: Promise<IDBDatabase> | undefined;

/** 保存待重试的原始 File，让刷新或响应丢失后仍能使用同一上传操作键。 */
export async function savePendingUpload(operationKey: string, file: File): Promise<void> {
  const db = await openDatabase();
  await transact(db, "readwrite", (store) => store.put(file, operationKey));
}

/** 读取原始字节供幂等重试；缺失表示本机无法确认此次上传结果。 */
export async function readPendingUpload(operationKey: string): Promise<File | undefined> {
  const db = await openDatabase();
  return transact<File | undefined>(db, "readonly", (store) => store.get(operationKey));
}

/** 上传已确认或草稿被放弃后清除本地字节副本，减少长期占用。 */
export async function deletePendingUpload(operationKey: string): Promise<void> {
  const db = await openDatabase();
  await transact(db, "readwrite", (store) => store.delete(operationKey));
}

/** 延迟打开独立 IndexedDB，避免模块加载时干扰不使用附件的工作台。 */
function openDatabase(): Promise<IDBDatabase> {
  if (database) return database;
  const opening = new Promise<IDBDatabase>((resolve, reject) => {
    if (!globalThis.indexedDB) {
      reject(new Error("浏览器无法安全保存待重试文件，请更换支持 IndexedDB 的浏览器。"));
      return;
    }
    const request = indexedDB.open(databaseName, 1);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(objectStoreName)) db.createObjectStore(objectStoreName);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("无法打开附件恢复存储。"));
    request.onblocked = () =>
      reject(new Error("附件恢复存储正在被其他页面占用，请关闭重复页面后重试。"));
  });
  database = opening.catch((error: unknown) => {
    database = undefined;
    throw error;
  });
  return database;
}

/** 用事务完成单个键读写，避免结果在连接关闭后丢失。 */
function transact<T>(
  db: IDBDatabase,
  mode: IDBTransactionMode,
  operation: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(objectStoreName, mode);
    const request = operation(transaction.objectStore(objectStoreName));
    let result: T | undefined;
    request.onsuccess = () => {
      result = request.result;
    };
    request.onerror = () => reject(request.error ?? new Error("附件恢复存储操作失败。"));
    transaction.oncomplete = () => resolve(result as T);
    transaction.onerror = () => reject(transaction.error ?? new Error("附件恢复存储事务失败。"));
    transaction.onabort = () => reject(transaction.error ?? new Error("附件恢复存储事务已取消。"));
  });
}
