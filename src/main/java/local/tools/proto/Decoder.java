package local.tools.proto;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Descriptors;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import com.google.gson.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;

import java.util.*;
import java.util.Base64;

public final class Decoder {

    public enum OutputFormat {
        JSON,          // standard protobuf JSON: bytes as base64 string
        TEXT
    }


    public record DecodeResult(String decoded, DecodeMode modeUsed, int messageByteLength) {}

    public record EncodeResult(String base64, String hex, int byteLength) {}

    private Decoder() {}

    public static DecodeResult decodeBase64(
            Descriptors.Descriptor descriptor,
            String base64,
            DecodeMode mode,
            OutputFormat format
    ) {
        byte[] allBytes = decodeBase64Lenient(base64);

        if (mode == DecodeMode.AUTO) {
            RuntimeException last = null;
            for (DecodeMode candidate : new DecodeMode[]{DecodeMode.RAW, DecodeMode.GRPC, DecodeMode.DELIMITED_VARINT}) {
                try {
                    return decodeBytes(descriptor, allBytes, candidate, format);
                } catch (RuntimeException e) {
                    last = e;
                }
            }
            throw last != null ? last : new RuntimeException("Unable to decode message");
        }

        return decodeBytes(descriptor, allBytes, mode, format);
    }

    private static DecodeResult decodeBytes(
            Descriptors.Descriptor descriptor,
            byte[] allBytes,
            DecodeMode mode,
            OutputFormat format
    ) {
        byte[] msgBytes = switch (mode) {
            case RAW -> allBytes;
            case GRPC -> unwrapGrpc(allBytes);
            case DELIMITED_VARINT -> unwrapDelimitedVarint(allBytes);
            case AUTO -> throw new IllegalStateException("AUTO should be handled earlier");
        };

        try {
            DynamicMessage msg = DynamicMessage.parseFrom(descriptor, msgBytes);

            String rendered = switch (format) {
                case JSON -> renderJsonBytesArray(msg, descriptor);
                case TEXT -> renderTextHuman(msg, descriptor);   // <-- change here
            };


            return new DecodeResult(rendered, mode, msgBytes.length);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to parse protobuf (" + mode + "): " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode (" + mode + "): " + e.getMessage(), e);
        }
    }

    /**
     * Builds a JsonFormat.Printer that also outputs default-valued fields
     * using the Set<FieldDescriptor> overload (protobuf-java-util 4.x).
     */
    private static JsonFormat.Printer jsonPrinterFor(Descriptors.Descriptor root) {
        Set<Descriptors.FieldDescriptor> fields = collectReachableFields(root);
        return JsonFormat.printer()
                .includingDefaultValueFields(fields)
                .preservingProtoFieldNames();
    }

    /**
     * Collects all field descriptors reachable from the root message by following message-typed fields,
     * so printing defaults works recursively for nested messages too.
     */
    private static Set<Descriptors.FieldDescriptor> collectReachableFields(Descriptors.Descriptor root) {
        Set<Descriptors.FieldDescriptor> out = new HashSet<>();
        Set<Descriptors.Descriptor> visited = new HashSet<>();
        Deque<Descriptors.Descriptor> q = new ArrayDeque<>();
        q.add(root);

        while (!q.isEmpty()) {
            Descriptors.Descriptor d = q.removeFirst();
            if (!visited.add(d)) continue;

            for (Descriptors.FieldDescriptor f : d.getFields()) {
                out.add(f);
                if (f.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
                    q.addLast(f.getMessageType());
                }
            }
        }

        return out;
    }

    public static EncodeResult encodeJson(Descriptors.Descriptor descriptor, String json) {
        try {
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);

            JsonFormat.parser()
                    .ignoringUnknownFields() // remove if you want strict JSON
                    .merge(json, builder);

            DynamicMessage msg = builder.build();
            byte[] bytes = msg.toByteArray();

            String base64 = Base64.getEncoder().encodeToString(bytes);
            String hex = HexFormat.of().formatHex(bytes);

            return new EncodeResult(base64, hex, bytes.length);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid JSON for " + descriptor.getFullName() + ": " + e.getMessage(), e
            );
        }
    }

    public static void validateJson(Descriptors.Descriptor descriptor, String json) {
        try {
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);

            JsonFormat.parser()
                    .ignoringUnknownFields() // remove if you want strict JSON
                    .merge(json, builder);

            builder.build();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "JSON does not match " + descriptor.getFullName() + ": " + e.getMessage(), e
            );
        }
    }

    private static byte[] decodeBase64Lenient(String base64) {
        String s = base64 == null ? "" : base64.trim().replaceAll("\\s+", "");
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Base64 payload is empty");
        }

        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException ignored) {
            try {
                return Base64.getUrlDecoder().decode(s);
            } catch (IllegalArgumentException e2) {
                throw new IllegalArgumentException("Base64 is invalid: " + e2.getMessage(), e2);
            }
        }
    }

    private static byte[] unwrapGrpc(byte[] bytes) {
        if (bytes.length < 5) {
            throw new RuntimeException("gRPC frame too short (need >= 5 bytes, got " + bytes.length + ")");
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        byte compressionFlag = bb.get();
        int len = bb.getInt();

        if (compressionFlag != 0) {
            throw new RuntimeException("gRPC compressed frames not supported (flag=" + compressionFlag + ")");
        }
        if (len < 0 || len > bb.remaining()) {
            throw new RuntimeException("gRPC length invalid: " + len + " (remaining=" + bb.remaining() + ")");
        }

        byte[] msg = new byte[len];
        bb.get(msg);
        return msg;
    }

    private static byte[] unwrapDelimitedVarint(byte[] bytes) {
        try {
            CodedInputStream cis = CodedInputStream.newInstance(bytes);
            int len = cis.readRawVarint32();
            if (len < 0) {
                throw new RuntimeException("Delimited length invalid: " + len);
            }
            if (len > cis.getBytesUntilLimit()) {
                throw new RuntimeException("Delimited length bigger than remaining bytes: " + len);
            }
            return cis.readRawBytes(len);
        } catch (Exception e) {
            throw new RuntimeException("Failed to unwrap delimited message: " + e.getMessage(), e);
        }
    }


    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private static String renderJsonBytesArray(DynamicMessage msg, Descriptors.Descriptor root) {
        JsonElement el = toJsonElement(msg, root, /*bytesAsArray=*/true);
        return PRETTY_GSON.toJson(el);
    }

    private static JsonElement toJsonElement(DynamicMessage msg, Descriptors.Descriptor desc, boolean bytesAsArray) {
        JsonObject obj = new JsonObject();

        // ordering rule: repeated fields first, then by field number
        List<Descriptors.FieldDescriptor> fields = new ArrayList<>(desc.getFields());
        fields.sort(Comparator
                .comparing((Descriptors.FieldDescriptor f) -> f.isRepeated() ? 0 : 1)
                .thenComparingInt(Descriptors.FieldDescriptor::getNumber));

        for (Descriptors.FieldDescriptor f : fields) {
            if (f.isRepeated()) {
                int n = msg.getRepeatedFieldCount(f);
                if (n == 0) continue;

                JsonArray arr = new JsonArray();
                for (int i = 0; i < n; i++) {
                    Object v = msg.getRepeatedField(f, i);
                    arr.add(scalarToJson(v, f, bytesAsArray));
                }
                obj.add(f.getName(), arr);

            } else {
                if (!msg.hasField(f)) continue;
                Object v = msg.getField(f);
                obj.add(f.getName(), scalarToJson(v, f, bytesAsArray));
            }
        }

        return obj;
    }

    private static JsonElement scalarToJson(Object v, Descriptors.FieldDescriptor f, boolean bytesAsArray) {
        return switch (f.getJavaType()) {
            case STRING -> new JsonPrimitive((String) v);

            case BOOLEAN -> new JsonPrimitive((Boolean) v);

            case INT -> new JsonPrimitive((Integer) v);
            case LONG -> new JsonPrimitive((Long) v);
            case FLOAT -> new JsonPrimitive((Float) v);
            case DOUBLE -> new JsonPrimitive((Double) v);

            case ENUM -> new JsonPrimitive(((Descriptors.EnumValueDescriptor) v).getName());

            case BYTE_STRING -> {
                ByteString bs = (ByteString) v;
                if (bytesAsArray) {
                    JsonArray a = new JsonArray();
                    byte[] b = bs.toByteArray();
                    for (byte x : b) a.add(x & 0xFF);
                    yield a;
                } else {
                    yield new JsonPrimitive(Base64.getEncoder().encodeToString(bs.toByteArray()));
                }
            }

            case MESSAGE -> toJsonElement((DynamicMessage) v, f.getMessageType(), bytesAsArray);
        };
    }

    private static String renderTextHuman(DynamicMessage msg, Descriptors.Descriptor desc) {
        StringBuilder sb = new StringBuilder();

        // same ordering rule as JSON: repeated first, then field number
        List<Descriptors.FieldDescriptor> fields = new ArrayList<>(desc.getFields());
        fields.sort(Comparator
                .comparing((Descriptors.FieldDescriptor f) -> f.isRepeated() ? 0 : 1)
                .thenComparingInt(Descriptors.FieldDescriptor::getNumber));

        for (Descriptors.FieldDescriptor f : fields) {
            if (f.isRepeated()) {
                int n = msg.getRepeatedFieldCount(f);
                for (int i = 0; i < n; i++) {
                    Object v = msg.getRepeatedField(f, i);
                    sb.append(f.getName()).append(": ").append(textValue(v, f)).append("\n");
                }
            } else {
                if (!msg.hasField(f)) continue;
                Object v = msg.getField(f);
                sb.append(f.getName()).append(": ").append(textValue(v, f)).append("\n");
            }
        }

        return sb.toString();
    }

    private static String textValue(Object v, Descriptors.FieldDescriptor f) {
        return switch (f.getJavaType()) {
            case STRING -> "\"" + v + "\"";
            case BOOLEAN, INT, LONG, FLOAT, DOUBLE -> String.valueOf(v);
            case ENUM -> ((Descriptors.EnumValueDescriptor) v).getName();

            case BYTE_STRING -> {
                byte[] b = ((ByteString) v).toByteArray();
                // show as decimal list like [255, 15]
                StringJoiner sj = new StringJoiner(", ", "[", "]");
                for (byte x : b) sj.add(String.valueOf(x & 0xFF));
                yield sj.toString();
            }

            case MESSAGE -> "{...}"; // or recursively render if you want
        };
    }


}
