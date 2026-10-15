package com.retrofrost.cubicalcompare.v5;

/** Android JNI interface backed by the compiled Rust cdylib, never by a Java stub. */
public final class NativeEngine {
    private NativeEngine() {}
    static { System.loadLibrary("cts_engine"); }
    public static native String scene(String projectJson, long frame);
    public static native long totalFrames(String projectJson);
}
