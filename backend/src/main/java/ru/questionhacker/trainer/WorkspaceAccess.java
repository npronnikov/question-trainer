package ru.questionhacker.trainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

@Component
public class WorkspaceAccess {

    private final Path root;
    private final long maxBytes;

    public WorkspaceAccess(AppProperties properties) {
        this.root = Path.of(properties.acp().workspace()).toAbsolutePath().normalize();
        this.maxBytes = properties.acp().maxFileBytes();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось создать ACP workspace: " + root, e);
        }
    }

    public String read(String path) {
        var target = resolve(path);
        try {
            long size = Files.size(target);
            if (size > maxBytes) {
                throw new IllegalArgumentException("Файл больше лимита " + maxBytes + " байт");
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать файл: " + target, e);
        }
    }

    public void write(String path, String content) {
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("Содержимое больше лимита " + maxBytes + " байт");
        }
        var target = resolve(path);
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось записать файл: " + target, e);
        }
    }

    public Path root() {
        return root;
    }

    private Path resolve(String rawPath) {
        var requested = Path.of(rawPath);
        var target = requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : root.resolve(requested).normalize();
        if (!target.startsWith(root)) {
            throw new SecurityException("ACP-запрос вышел за пределы workspace");
        }
        return target;
    }
}
