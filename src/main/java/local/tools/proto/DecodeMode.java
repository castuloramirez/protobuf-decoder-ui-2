package local.tools.proto;

/**
 * How to interpret the incoming bytes (after base64 decode).
 */
public enum DecodeMode {
    /** Interpret bytes as a plain protobuf message. */
    RAW,

    /**
     * gRPC frame: 1 byte compression flag + 4 bytes big-endian message length + message bytes.
     */
    GRPC,

    /**
     * "Delimited" protobuf message: varint length prefix followed by message bytes.
     * (Common with parseDelimitedFrom / writeDelimitedTo.)
     */
    DELIMITED_VARINT,

    /** Try RAW, then GRPC, then DELIMITED_VARINT until one works. */
    AUTO
}
