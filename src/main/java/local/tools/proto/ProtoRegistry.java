package local.tools.proto;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads a protoc descriptor set (FileDescriptorSet) and exposes message descriptors by full name.
 */
public final class ProtoRegistry {

    private final Map<String, Descriptors.Descriptor> byFullName;

    private ProtoRegistry(Map<String, Descriptors.Descriptor> byFullName) {
        this.byFullName = Collections.unmodifiableMap(byFullName);
    }

    /**
     * Load a descriptor set from the classpath.
     * Example: "protos/all.desc" in src/main/resources.
     */
    public static ProtoRegistry loadFromResource(String resourcePath) {
        try (InputStream in = ProtoRegistry.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Descriptor resource not found: " + resourcePath);
            }
            return fromInputStream(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read descriptor resource: " + resourcePath, e);
        }
    }

    /** Load a descriptor set from a file path. */
    public static ProtoRegistry loadFromFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return fromInputStream(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read descriptor file: " + file, e);
        }
    }

    private static ProtoRegistry fromInputStream(InputStream in) throws IOException {
        DescriptorProtos.FileDescriptorSet fds = DescriptorProtos.FileDescriptorSet.parseFrom(in);

        // Index FileDescriptorProto by name
        Map<String, DescriptorProtos.FileDescriptorProto> protosByName = new HashMap<>();
        for (DescriptorProtos.FileDescriptorProto fdp : fds.getFileList()) {
            protosByName.put(fdp.getName(), fdp);
        }

        // Build FileDescriptors with dependency resolution
        Map<String, Descriptors.FileDescriptor> built = new HashMap<>();
        for (String name : protosByName.keySet()) {
            buildFileDescriptor(name, protosByName, built);
        }

        // Collect top-level + nested message types
        Map<String, Descriptors.Descriptor> messages = new TreeMap<>();
        for (Descriptors.FileDescriptor fd : built.values()) {
            for (Descriptors.Descriptor md : fd.getMessageTypes()) {
                collectMessagesRecursive(md, messages);
            }
        }

        return new ProtoRegistry(messages);
    }

    private static Descriptors.FileDescriptor buildFileDescriptor(
            String name,
            Map<String, DescriptorProtos.FileDescriptorProto> protosByName,
            Map<String, Descriptors.FileDescriptor> built
    ) {
        if (built.containsKey(name)) {
            return built.get(name);
        }

        DescriptorProtos.FileDescriptorProto fdp = protosByName.get(name);
        if (fdp == null) {
            throw new IllegalStateException("Missing FileDescriptorProto for: " + name);
        }

        try {
            Descriptors.FileDescriptor[] deps = new Descriptors.FileDescriptor[fdp.getDependencyCount()];
            for (int i = 0; i < fdp.getDependencyCount(); i++) {
                String depName = fdp.getDependency(i);
                deps[i] = buildFileDescriptor(depName, protosByName, built);
            }

            Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(fdp, deps);
            built.put(name, fd);
            return fd;
        } catch (Descriptors.DescriptorValidationException e) {
            throw new RuntimeException("Failed to build FileDescriptor for: " + name, e);
        }
    }

    private static void collectMessagesRecursive(Descriptors.Descriptor d, Map<String, Descriptors.Descriptor> out) {
        out.put(d.getFullName(), d);
        for (Descriptors.Descriptor nested : d.getNestedTypes()) {
            collectMessagesRecursive(nested, out);
        }
    }

    public List<String> listMessageTypes() {
        return new ArrayList<>(byFullName.keySet());
    }

    public Descriptors.Descriptor get(String fullName) {
        Descriptors.Descriptor d = byFullName.get(fullName);
        if (d == null) {
            throw new IllegalArgumentException("Unknown message type: " + fullName);
        }
        return d;
    }

    public static ProtoRegistry loadFromBytes(byte[] descriptorSetBytes) {
        try (InputStream in = new java.io.ByteArrayInputStream(descriptorSetBytes)) {
            return fromInputStream(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read descriptor bytes", e);
        }
    }

}
