package io.github.cobylinweiqi.deskly.probe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 直接从 APK 里读 DEX 的类名清单。
 *
 * <p>为什么不用 {@code DexFile}：它在 API 26 之后被限制，且一个 APK 常有多份 dex，
 * {@code DexFile.loadDex} 只吃单个文件。这里按 DEX 头部偏移自己解析 class_defs，
 * 不依赖任何隐藏 API，也不加载目标代码——纯字节读取，零副作用。
 */
public final class DexLister {

    private static final int HEADER_STRING_IDS_SIZE = 0x38;
    private static final int HEADER_STRING_IDS_OFF = 0x3C;
    private static final int HEADER_TYPE_IDS_SIZE = 0x40;
    private static final int HEADER_TYPE_IDS_OFF = 0x44;
    private static final int HEADER_CLASS_DEFS_SIZE = 0x60;
    private static final int HEADER_CLASS_DEFS_OFF = 0x64;
    private static final int CLASS_DEF_ITEM_SIZE = 32;

    private DexLister() {
    }

    /** 返回全部类名（点分形式），按字典序。解析失败的 dex 会被跳过而不是整体中断。 */
    public static List<String> listClassNames(File apk) throws IOException {
        List<String> out = new ArrayList<>();
        ZipFile zf = null;
        try {
            zf = new ZipFile(apk);
            Enumeration<? extends ZipEntry> es = zf.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                String name = e.getName();
                if (!isDexEntry(name)) {
                    continue;
                }
                byte[] data = readFully(zf.getInputStream(e));
                parseDex(data, out);
            }
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (IOException ignored) {
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    private static boolean isDexEntry(String n) {
        return n.equals("classes.dex") || (n.startsWith("classes") && n.endsWith(".dex"));
    }

    private static void parseDex(byte[] data, List<String> out) {
        try {
            if (data == null || data.length < 0x70) {
                return;
            }
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            // magic 校验：dex\n035\0 之类
            if (b.get(0) != 'd' || b.get(1) != 'e' || b.get(2) != 'x') {
                return;
            }

            int stringIdsSize = b.getInt(HEADER_STRING_IDS_SIZE);
            int stringIdsOff = b.getInt(HEADER_STRING_IDS_OFF);
            int typeIdsOff = b.getInt(HEADER_TYPE_IDS_OFF);
            int typeIdsSize = b.getInt(HEADER_TYPE_IDS_SIZE);
            int classDefsSize = b.getInt(HEADER_CLASS_DEFS_SIZE);
            int classDefsOff = b.getInt(HEADER_CLASS_DEFS_OFF);

            if (stringIdsSize <= 0 || typeIdsSize <= 0 || classDefsSize <= 0) {
                return;
            }
            if (stringIdsOff <= 0 || typeIdsOff <= 0 || classDefsOff <= 0) {
                return;
            }

            int[] stringOffsets = new int[stringIdsSize];
            for (int i = 0; i < stringIdsSize; i++) {
                stringOffsets[i] = b.getInt(stringIdsOff + i * 4);
            }

            for (int i = 0; i < classDefsSize; i++) {
                int base = classDefsOff + i * CLASS_DEF_ITEM_SIZE;
                if (base + CLASS_DEF_ITEM_SIZE > data.length) {
                    break;
                }
                int classIdx = b.getInt(base);
                if (classIdx < 0 || classIdx >= typeIdsSize) {
                    continue;
                }
                int descriptorIdx = b.getInt(typeIdsOff + classIdx * 4);
                if (descriptorIdx < 0 || descriptorIdx >= stringIdsSize) {
                    continue;
                }
                String descriptor = readString(b, stringOffsets[descriptorIdx], data.length);
                String cls = descriptorToClassName(descriptor);
                if (cls != null) {
                    out.add(cls);
                }
            }
        } catch (Throwable ignored) {
            // 单个 dex 损坏不影响其余
        }
    }

    /** "Lcom/a/B;" -> "com.a.B"；数组/基础类型返回 null。 */
    private static String descriptorToClassName(String d) {
        if (d == null || d.length() < 3) {
            return null;
        }
        if (d.charAt(0) != 'L' || d.charAt(d.length() - 1) != ';') {
            return null;
        }
        return d.substring(1, d.length() - 1).replace('/', '.');
    }

    private static String readString(ByteBuffer b, int off, int limit) {
        int p = off;
        // 跳过 uleb128 的 utf16_size
        while (p < limit && (b.get(p) & 0x80) != 0) {
            p++;
        }
        p++;

        StringBuilder sb = new StringBuilder(48);
        while (p < limit) {
            int a = b.get(p++) & 0xFF;
            if (a == 0) {
                break;
            }
            if (a < 0x80) {
                sb.append((char) a);
            } else if ((a & 0xE0) == 0xC0 && p < limit) {
                int a2 = b.get(p++) & 0x3F;
                sb.append((char) (((a & 0x1F) << 6) | a2));
            } else if ((a & 0xF0) == 0xE0 && p + 1 < limit) {
                int a2 = b.get(p++) & 0x3F;
                int a3 = b.get(p++) & 0x3F;
                sb.append((char) (((a & 0x0F) << 12) | (a2 << 6) | a3));
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private static byte[] readFully(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
