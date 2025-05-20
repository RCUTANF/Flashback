package com.moulberry.flashback.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ReplayMergerTool {
    private static final int MAGIC = 0x464C4248; // "FLBH" 魔数

    private final Path overworld;
    private final Path nether;
    private final Path output;
    private final String overworldDimId;
    private final String netherDimId;

    public ReplayMergerTool(Path overworld, Path nether, Path output,
                            String overworldDimId, String netherDimId) {
        this.overworld = overworld;
        this.nether = nether;
        this.output = output;
        this.overworldDimId = overworldDimId;
        this.netherDimId = netherDimId;
    }

    public void mergeReplays() throws IOException {
        System.out.println("正在合并录像...");
        System.out.println("主世界录像: " + overworld);
        System.out.println("下界录像: " + nether);
        System.out.println("输出路径: " + output);

        // 读取录像文件
        ZipFile overworldZip = new ZipFile(overworld.toFile());
        ZipFile netherZip = new ZipFile(nether.toFile());

        // 读取元数据
        JsonObject overworldMeta = readMetadata(overworldZip);
        JsonObject netherMeta = readMetadata(netherZip);

        if (overworldMeta == null || netherMeta == null) {
            throw new IOException("无法读取录像元数据");
        }

        // 合并元数据
        JsonObject mergedMeta = mergeMetadata(overworldMeta, netherMeta);

        // 提取所有事件
        List<ReplayEvent> events = new ArrayList<>();
        events.addAll(extractEvents(overworldZip, overworldDimId));
        events.addAll(extractEvents(netherZip, netherDimId));

        // 按时间排序事件
        events.sort(Comparator.comparingLong(ReplayEvent::getGameTime));

        // 创建合并后的录像文件
        createMergedReplay(overworldZip, events, mergedMeta);

        overworldZip.close();
        netherZip.close();

        System.out.println("合并完成！");
    }

    private JsonObject readMetadata(ZipFile zipFile) throws IOException {
        ZipEntry entry = zipFile.getEntry("metadata.json");
        if (entry == null) {
            return null;
        }

        try (InputStream is = zipFile.getInputStream(entry);
             Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private JsonObject mergeMetadata(JsonObject overworld, JsonObject nether) {
        // 以主世界元数据为基础
        JsonObject merged = deepCopy(overworld);

        // 合并dimensions数组
        if (merged.has("dimensions") && nether.has("dimensions")) {
            JsonArray overworldDims = merged.getAsJsonArray("dimensions");
            JsonArray netherDims = nether.getAsJsonArray("dimensions");

            // 先将已有的维度ID收集到Set中
            Set<String> existingDims = new HashSet<>();
            for (JsonElement elem : overworldDims) {
                existingDims.add(elem.getAsString());
            }

            // 添加不存在的维度
            for (JsonElement elem : netherDims) {
                String dimId = elem.getAsString();
                if (!existingDims.contains(dimId)) {
                    overworldDims.add(elem);
                }
            }
        }

        // 如果主世界没有dimensions字段但下界有，则添加
        if (!merged.has("dimensions") && nether.has("dimensions")) {
            merged.add("dimensions", nether.getAsJsonArray("dimensions"));
        }

        // 更新录像描述
        if (merged.has("description")) {
            String desc = merged.get("description").getAsString();
            merged.addProperty("description", desc + " (合并录像)");
        }

        return merged;
    }

    private List<ReplayEvent> extractEvents(ZipFile zipFile, String dimension) throws IOException {
        List<ReplayEvent> events = new ArrayList<>();

        // 从 ZIP 中查找录像文件
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String fileName = new File(entry.getName()).getName();

            // 跳过元数据和图标
            if (fileName.equals("metadata.json") || fileName.equals("icon.png") ||
                    entry.getName().startsWith("level_chunk_caches/")) continue;

            // 读取文件内容
            byte[] data;
            try (InputStream is = zipFile.getInputStream(entry)) {
                data = readAllBytes(is);
            }

            // 读取文件
            ByteBuffer buf = ByteBuffer.wrap(data);

            // 检查魔数
            if (buf.getInt() != MAGIC) continue;

            // 跳过动作注册
            int actionCount = readVarInt(buf);
            for (int i = 0; i < actionCount; i++) {
                readString(buf);
            }

            // 跳过快照大小和快照数据
            int snapshotSize = buf.getInt();
            buf.position(buf.position() + snapshotSize);

            // 读取所有事件
            while (buf.hasRemaining()) {
                try {
                    int actionId = readVarInt(buf);
                    int size = buf.getInt();

                    if (size <= 0 || size > buf.remaining()) {
                        System.err.println("警告：无效的事件大小: " + size);
                        break;
                    }

                    // 提取事件数据
                    byte[] eventData = new byte[size + getVarIntSize(actionId) + 4];
                    ByteBuffer eventBuf = ByteBuffer.wrap(eventData);
                    writeVarInt(eventBuf, actionId);
                    eventBuf.putInt(size);

                    byte[] actionData = new byte[size];
                    buf.get(actionData);
                    eventBuf.put(actionData);

                    // 检查是否是时间戳事件
                    ByteBuffer actionBuf = ByteBuffer.wrap(actionData);
                    Long gameTime = extractGameTime(actionBuf);

                    if (gameTime != null) {
                        events.add(new ReplayEvent(gameTime, dimension, eventData));
                    } else {
                        // 非时间戳事件，默认添加到最早的时间点
                        events.add(new ReplayEvent(0, dimension, eventData));
                    }
                } catch (Exception e) {
                    System.err.println("警告：解析事件时出错: " + e.getMessage());
                    break;
                }
            }
        }

        return events;
    }

    // 尝试提取事件中的游戏时间
    private Long extractGameTime(ByteBuffer buf) {
        if (buf.remaining() >= 8) {
            try {
                return buf.getLong();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private void createMergedReplay(ZipFile baseZip, List<ReplayEvent> events, JsonObject metadata) throws IOException {
        // 准备合并录像
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(output.toFile()))) {
            // 添加元数据
            zos.putNextEntry(new ZipEntry("metadata.json"));
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            byte[] metadataBytes = gson.toJson(metadata).getBytes(StandardCharsets.UTF_8);
            zos.write(metadataBytes);
            zos.closeEntry();

            // 复制图标
            ZipEntry iconEntry = baseZip.getEntry("icon.png");
            if (iconEntry != null) {
                zos.putNextEntry(new ZipEntry("icon.png"));
                try (InputStream is = baseZip.getInputStream(iconEntry)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
            }

            // 复制地形缓存
            Enumeration<? extends ZipEntry> entries = baseZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("level_chunk_caches/")) {
                    zos.putNextEntry(new ZipEntry(entry.getName()));
                    try (InputStream is = baseZip.getInputStream(entry)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }
                    zos.closeEntry();
                }
            }

            // 创建合并的录像文件
            zos.putNextEntry(new ZipEntry("replay.flbh"));

            // 提取动作数据和快照
            byte[] actionsData = extractActionsData(baseZip);
            byte[] snapshot = extractSnapshot(baseZip);

            if (actionsData == null || snapshot == null) {
                throw new IOException("提取录像数据失败");
            }

            // 写入魔数
            ByteBuffer header = ByteBuffer.allocate(4);
            header.putInt(MAGIC);
            zos.write(header.array());

            // 写入动作数据
            zos.write(actionsData);

            // 写入快照大小和快照数据
            ByteBuffer snapshotHeader = ByteBuffer.allocate(4);
            snapshotHeader.putInt(snapshot.length);
            zos.write(snapshotHeader.array());
            zos.write(snapshot);

            // 合并并写入所有事件
            Map<String, List<ReplayEvent>> eventsByDimension = events.stream()
                    .collect(Collectors.groupingBy(ReplayEvent::getDimension));

            // 获取所有维度的时间范围
            Map<String, long[]> timeRanges = new HashMap<>();
            for (Map.Entry<String, List<ReplayEvent>> entry : eventsByDimension.entrySet()) {
                List<ReplayEvent> dimEvents = entry.getValue();
                if (!dimEvents.isEmpty()) {
                    long minTime = dimEvents.stream()
                            .mapToLong(ReplayEvent::getGameTime)
                            .min().orElse(0);
                    long maxTime = dimEvents.stream()
                            .mapToLong(ReplayEvent::getGameTime)
                            .max().orElse(0);
                    timeRanges.put(entry.getKey(), new long[]{minTime, maxTime});
                }
            }

            // 计算时间断点
            long[] breakpoints = calculateBreakpoints(timeRanges);

            // 根据时间断点分配事件
            for (ReplayEvent event : events) {
                if (event.getGameTime() > 0) {
                    // 根据游戏时间计算应该写入哪个文件区域
                    int fileIndex = findFileIndex(event.getGameTime(), breakpoints);
                    String eventDim = event.getDimension();

                    // 确定事件是否应该写入
                    // 如果在当前维度的录像范围内则写入
                    if (timeRanges.containsKey(eventDim)) {
                        long[] range = timeRanges.get(eventDim);
                        if (event.getGameTime() >= range[0] && event.getGameTime() <= range[1]) {
                            zos.write(event.getData());
                        }
                    }
                } else {
                    // 不带时间戳的事件默认添加
                    zos.write(event.getData());
                }
            }

            zos.closeEntry();
        }
    }

    private long[] calculateBreakpoints(Map<String, long[]> timeRanges) {
        // 查找最早和最晚的时间
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;

        for (long[] range : timeRanges.values()) {
            minTime = Math.min(minTime, range[0]);
            maxTime = Math.max(maxTime, range[1]);
        }

        // 如果只有一个录像文件
        if (timeRanges.size() <= 1) {
            return new long[0];
        }

        int numFiles = timeRanges.size();
        long intervalSize = (maxTime - minTime) / numFiles;
        long[] breakpoints = new long[numFiles - 1];

        // 设置断点
        for (int i = 0; i < numFiles - 1; i++) {
            breakpoints[i] = minTime + (i + 1) * intervalSize;
        }

        return breakpoints;
    }

    private int findFileIndex(long gameTime, long[] breakpoints) {
        for (int i = 0; i < breakpoints.length; i++) {
            if (gameTime < breakpoints[i]) {
                return i;
            }
        }
        return breakpoints.length; // 最后一个文件
    }

    private byte[] extractActionsData(ZipFile zipFile) {
        // 从 ZIP 中查找第一个录像文件
        try {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String fileName = new File(entry.getName()).getName();

                // 跳过元数据和图标
                if (fileName.equals("metadata.json") || fileName.equals("icon.png") ||
                        entry.getName().startsWith("level_chunk_caches/")) continue;

                // 读取文件内容
                byte[] data;
                try (InputStream is = zipFile.getInputStream(entry)) {
                    data = readAllBytes(is);
                }

                // 读取文件开头
                ByteBuffer buf = ByteBuffer.wrap(data);

                // 检查魔数
                if (buf.getInt() != MAGIC) continue;

                // 读取动作数量
                int actionCount = readVarInt(buf);

                // 创建包含动作数量的字节数组
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                writeVarInt(dos, actionCount);

                // 读取并复制所有动作名称
                for (int i = 0; i < actionCount; i++) {
                    String actionName = readString(buf);
                    byte[] nameBytes = actionName.getBytes(StandardCharsets.UTF_8);
                    writeVarInt(dos, nameBytes.length);
                    dos.write(nameBytes);
                }

                return baos.toByteArray();
            }
        } catch (IOException e) {
            System.err.println("提取动作数据时出错: " + e.getMessage());
        }
        return null;
    }

    private byte[] extractSnapshot(ZipFile zipFile) {
        // 从 ZIP 中查找第一个录像文件
        try {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String fileName = new File(entry.getName()).getName();

                // 跳过元数据和图标
                if (fileName.equals("metadata.json") || fileName.equals("icon.png") ||
                        entry.getName().startsWith("level_chunk_caches/")) continue;

                // 读取文件内容
                byte[] data;
                try (InputStream is = zipFile.getInputStream(entry)) {
                    data = readAllBytes(is);
                }

                // 读取文件
                ByteBuffer buf = ByteBuffer.wrap(data);

                // 检查魔数
                if (buf.getInt() != MAGIC) continue;

                // 跳过动作注册
                int actionCount = readVarInt(buf);
                for (int i = 0; i < actionCount; i++) {
                    readString(buf);
                }

                // 读取快照大小
                int snapshotSize = buf.getInt();

                // 提取快照数据
                byte[] snapshot = new byte[snapshotSize];
                buf.get(snapshot);
                return snapshot;
            }
        } catch (IOException e) {
            System.err.println("提取快照时出错: " + e.getMessage());
        }
        return null;
    }

    // 格式解析工具方法
    private int readVarInt(ByteBuffer buf) {
        int value = 0;
        int size = 0;
        int b;
        while (((b = buf.get() & 0xFF) & 0x80) != 0) {
            value |= (b & 0x7F) << (size++ * 7);
            if (size > 5) {
                throw new RuntimeException("VarInt太长");
            }
        }
        return value | ((b & 0x7F) << (size * 7));
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    private void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private void writeVarInt(ByteBuffer buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
    }

    private int getVarIntSize(int value) {
        if ((value & 0xFFFFFF80) == 0) return 1;
        if ((value & 0xFFFFC000) == 0) return 2;
        if ((value & 0xFFE00000) == 0) return 3;
        if ((value & 0xF0000000) == 0) return 4;
        return 5;
    }

    private String readString(ByteBuffer buf) {
        int length = readVarInt(buf);
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private JsonObject deepCopy(JsonObject src) {
        return JsonParser.parseString(src.toString()).getAsJsonObject();
    }

    // 事件类
    static class ReplayEvent {
        private final long gameTime;
        private final String dimension;
        private final byte[] data;

        public ReplayEvent(long gameTime, String dimension, byte[] data) {
            this.gameTime = gameTime;
            this.dimension = dimension;
            this.data = data;
        }

        public long getGameTime() {
            return gameTime;
        }

        public String getDimension() {
            return dimension;
        }

        public byte[] getData() {
            return data;
        }
    }

    // 命令行入口
    public static void main(String[] args) {
        if (args.length < 5) {
            System.out.println("用法: ReplayMergerTool <主世界录像路径> <下界录像路径> <输出路径> <主世界维度标识> <下界维度标识>");
            System.out.println("例如: ReplayMergerTool ./overworld_replay ./nether_replay ./merged_replay overworld nether");
            return;
        }

        try {
            ReplayMergerTool merger = new ReplayMergerTool(
                    Paths.get(args[0]),
                    Paths.get(args[1]),
                    Paths.get(args[2]),
                    args[3],
                    args[4]
            );

            merger.mergeReplays();
        } catch (Exception e) {
            System.err.println("合并失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}