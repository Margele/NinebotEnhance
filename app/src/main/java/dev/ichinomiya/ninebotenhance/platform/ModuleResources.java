package dev.ichinomiya.ninebotenhance.platform;

import dev.ichinomiya.ninebotenhance.core.Streams;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/** Read the module APK explicitly: a parent-first host class loader may contain its own META-INF/NOTICE.txt. */
public final class ModuleResources {
    private static volatile String apk;
    public static void initialize(String moduleApk) { apk = moduleApk; }
    public static String text(String resource) throws IOException {
        String source = apk;
        if (source == null) throw new IOException("模块资源尚未初始化，请重新打开九号出行");
        try (ZipFile file = new ZipFile(source)) {
            ZipEntry entry = file.getEntry(resource);
            if (entry == null || entry.getSize() < 0 || entry.getSize() > 128 * 1024) throw new IOException("安装包中缺少有效的许可证文件");
            try (InputStream input = file.getInputStream(entry)) { return new String(Streams.readAll(input, 256 * 1024), StandardCharsets.UTF_8); }
        }
    }
    private ModuleResources() {}
}
