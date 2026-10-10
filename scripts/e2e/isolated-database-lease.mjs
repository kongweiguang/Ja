// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { execFile } from "node:child_process";
import { mkdir, stat, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

/** 共用短小 JDK probe，CLI 与桌面都以真实 FileLock 确认 owner 已释放数据库。 @author kongweiguang */
export async function compileDatabaseLeaseProbe(javac, directory) {
  const classes = join(directory, "database-lease-probe");
  await mkdir(classes, { recursive: true });
  const source = join(directory, "DatabaseLeaseProbe.java");
  await writeFile(
    source,
    [
      "import java.nio.channels.FileChannel;",
      "import java.nio.channels.FileLock;",
      "import java.nio.channels.OverlappingFileLockException;",
      "import java.nio.file.Path;",
      "import java.nio.file.StandardOpenOption;",
      "public final class DatabaseLeaseProbe {",
      "  public static void main(String[] args) throws Exception {",
      "    try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {",
      "      try (FileLock lock = channel.tryLock()) {",
      "        if (lock == null) System.exit(2);",
      "      } catch (OverlappingFileLockException held) { System.exit(2); }",
      "    }",
      "  }",
      "}",
    ].join("\n"),
    "utf8",
  );
  await execFileAsync(javac, ["-encoding", "UTF-8", "-d", classes, source], {
    windowsHide: true,
    timeout: 30_000,
  });
  return classes;
}

/** 仅检查调用方的隔离 JA_HOME，缺失锁文件或无法取得独占锁均不能冒充释放成功。 @author kongweiguang */
export async function assertDatabaseLeaseReleased(java, classDirectory, jaHome, label) {
  const lockPath = join(jaHome, "data", "ja.db.lock");
  await stat(lockPath);
  try {
    await execFileAsync(java, ["-cp", classDirectory, "DatabaseLeaseProbe", lockPath], {
      windowsHide: true,
      timeout: 10_000,
      maxBuffer: 64 * 1024,
    });
  } catch {
    throw new Error(`${label} still holds the isolated SQLite database lease`);
  }
}
