package com.shirokai.commands.logging;

import com.shirokai.commands.util.CommandPath;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;

public final class CommandAuditLogger implements AutoCloseable {
  private final Path file;
  private final ThreadPoolExecutor writer =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1024),
          r -> {
            Thread t = new Thread(r, "shirokai-audit");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.DiscardPolicy());
  private final Map<UUID, Long> last = new ConcurrentHashMap<>();

  public CommandAuditLogger(Path directory) {
    file = directory.resolve("audit.log");
  }

  public void blocked(UUID uuid, String name, String command, String rule) {
    long now = System.nanoTime();
    Long prior = last.put(uuid, now);
    if (prior != null && now - prior < TimeUnit.SECONDS.toNanos(2)) return;
    record(
        "BLOCK uuid="
            + uuid
            + " name="
            + name
            + " command="
            + CommandPath.redacted(command)
            + " rule="
            + CommandPath.redacted(rule));
  }

  public void forget(UUID uuid) {
    last.remove(uuid);
  }

  public void admin(String name, String operation) {
    record("ADMIN name=" + name + " operation=" + operation);
  }

  private void record(String message) {
    String line = Instant.now() + " " + message.replaceAll("[\\r\\n]", " ") + "\n";
    writer.execute(
        () -> {
          try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
          } catch (Exception e) {
            LoggerFactory.getLogger("shirokai_commands").error("Could not write audit log", e);
          }
        });
  }

  public void close() {
    writer.shutdown();
    try {
      if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
