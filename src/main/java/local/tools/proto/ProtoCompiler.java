package local.tools.proto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

final class ProtoCompiler {

    record CompileResult(byte[] descriptorSetBytes, List<String> messageTypes) {}

    /**
     * Compiles a single proto file content using system protoc.
     *
     * @param protoContent content of the .proto editor
     * @param includePaths optional extra -I include paths (folders containing imported protos)
     */
    static CompileResult compileSingleFile(String protoContent, List<String> includePaths) {
        Objects.requireNonNull(protoContent, "protoContent");
        includePaths = includePaths == null ? List.of() : includePaths;

        try {
            Path dir = Files.createTempDirectory("proto-ui-");
            Path protoFile = dir.resolve("unnamed.proto");
            Path outDesc = dir.resolve("all.desc");

            Files.writeString(protoFile, protoContent, StandardCharsets.UTF_8);

            List<String> cmd = new ArrayList<>();
            cmd.add("protoc");
            cmd.add("--include_imports");
            cmd.add("--descriptor_set_out=" + outDesc.toAbsolutePath());

            // include current temp dir first (so unnamed.proto and local imports resolve)
            cmd.add("-I");
            cmd.add(dir.toAbsolutePath().toString());

            // user-provided include paths
            for (String p : includePaths) {
                if (p == null || p.isBlank()) continue;
                cmd.add("-I");
                cmd.add(p.trim());
            }

            cmd.add(protoFile.toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = proc.waitFor();

            if (code != 0) {
                throw new IllegalArgumentException("protoc failed:\n" + output);
            }

            byte[] descBytes = Files.readAllBytes(outDesc);

            // Build registry to list message types
            ProtoRegistry reg = ProtoRegistry.loadFromBytes(descBytes);
            return new CompileResult(descBytes, reg.listMessageTypes());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Compile failed: " + e.getMessage(), e);
        }
    }
}
