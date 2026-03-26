package local.tools.proto;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.Javalin;

import java.util.*;
import java.util.stream.Collectors;

public final class App {

    private static final Gson GSON = new Gson();

    // ----- Response DTOs (Jackson-friendly) -----
    record CompileResponse(String schemaId, List<String> types) {}
    record DecodeResponse(String decoded, String modeUsed, int messageByteLength) {}
    record EncodeResponse(String base64, String hex, int byteLength) {}
    record OkResponse(boolean ok) {}

    public static void main(String[] args) {
        int port = 7070;

        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            }
        }

        // In-memory compiled schema cache
        SchemaCache schemaCache = new SchemaCache();

        Javalin app = Javalin.create(cfg -> cfg.staticFiles.add(staticFiles -> {
            staticFiles.hostedPath = "/";
            staticFiles.directory = "/web";
            staticFiles.precompress = false;
        })).start(port);

        /* ---------------- error handling ---------------- */

        app.exception(IllegalArgumentException.class, (e, ctx) ->
                ctx.status(400).json(Map.of("message", e.getMessage())));
        app.exception(RuntimeException.class, (e, ctx) ->
                ctx.status(400).json(Map.of("message", e.getMessage())));
        app.exception(Exception.class, (e, ctx) ->
                ctx.status(500).json(Map.of("message", String.valueOf(e.getMessage()))));

        /* ---------------- default registry (optional) ---------------- */

        ProtoRegistry tmpRegistry;
        try {
            tmpRegistry = ProtoRegistry.loadFromResource("protos/all.desc");
        } catch (Exception ignored) {
            tmpRegistry = null;
        }
        final ProtoRegistry defaultRegistry = tmpRegistry;

        /* ---------------- compile proto ---------------- */

        app.post("/api/compile", ctx -> {
            JsonObject req = GSON.fromJson(ctx.body(), JsonObject.class);

            String proto = require(req, "proto");
            String includePathsRaw = optional(req, "includePaths").orElse("");

            List<String> includePaths = includePathsRaw.isBlank()
                    ? List.of()
                    : Arrays.stream(includePathsRaw.split("\\R"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());

            ProtoCompiler.CompileResult compiled =
                    ProtoCompiler.compileSingleFile(proto, includePaths);

            String schemaId = schemaCache.put(compiled.descriptorSetBytes());

            ctx.json(new CompileResponse(schemaId, compiled.messageTypes()));
        });

        /* ---------------- decode ---------------- */

        app.post("/api/decode", ctx -> {
            JsonObject req = GSON.fromJson(ctx.body(), JsonObject.class);

            String type = require(req, "type");
            String base64 = require(req, "base64");

            DecodeMode mode = DecodeMode.valueOf(optional(req, "mode").orElse("AUTO").toUpperCase());
            Decoder.OutputFormat format = Decoder.OutputFormat.valueOf(optional(req, "format").orElse("JSON").toUpperCase());

            // schemaId is optional
            String schemaId = optional(req, "schemaId").orElse(null);

            ProtoRegistry registryToUse;
            if (schemaId != null && !schemaId.isBlank()) {
                byte[] descBytes = schemaCache.get(schemaId);
                registryToUse = ProtoRegistry.loadFromBytes(descBytes);
            } else {
                if (defaultRegistry == null) {
                    throw new IllegalArgumentException("No schemaId provided and no default protos/all.desc loaded. Use /api/compile first.");
                }
                registryToUse = defaultRegistry;
            }

            Decoder.DecodeResult result =
                    Decoder.decodeBase64(registryToUse.get(type), base64, mode, format);

            ctx.json(new DecodeResponse(result.decoded(), result.modeUsed().name(), result.messageByteLength()));
        });

        /* ---------------- encode ---------------- */

        app.post("/api/encode", ctx -> {
            JsonObject req = GSON.fromJson(ctx.body(), JsonObject.class);

            String schemaId = require(req, "schemaId");
            String type = require(req, "type");
            String json = require(req, "json");

            byte[] descBytes = schemaCache.get(schemaId);
            ProtoRegistry registry = ProtoRegistry.loadFromBytes(descBytes);

            Decoder.EncodeResult r = Decoder.encodeJson(registry.get(type), json);

            ctx.json(new EncodeResponse(r.base64(), r.hex(), r.byteLength()));
        });

        /* ---------------- validate json ---------------- */

        app.post("/api/validateJson", ctx -> {
            System.out.println("Juajuaja"+ctx.body());
            JsonObject req = GSON.fromJson(ctx.body(), JsonObject.class);

            String schemaId = require(req, "schemaId");
            String type = require(req, "type");
            String json = require(req, "json");

            byte[] descBytes = schemaCache.get(schemaId);
            ProtoRegistry registry = ProtoRegistry.loadFromBytes(descBytes);

            Decoder.validateJson(registry.get(type), json);

            ctx.json(new OkResponse(true));
        });

        /* ---------------- list message types (legacy UI) ---------------- */

        app.get("/api/types", ctx -> {
            if (defaultRegistry == null) {
                ctx.json(List.of()); // return valid JSON, no error in UI
            } else {
                ctx.json(defaultRegistry.listMessageTypes());
            }
        });

        System.out.println("Open http://localhost:" + port);
    }

    /* ---------------- helpers ---------------- */

    private static String require(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return obj.get(key).getAsString();
    }

    private static Optional<String> optional(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return Optional.empty();
        }
        return Optional.ofNullable(obj.get(key).getAsString());
    }
}
