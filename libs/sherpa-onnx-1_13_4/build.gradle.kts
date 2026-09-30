plugins {
    id("org.autojs.build.local-arr-register-convention")
}

localAars {
    // Renamed-onnxruntime build of the original sherpa-onnx-1.13.4.aar.
    // The original AAR bundles jni/<abi>/libonnxruntime.so (onnxruntime 1.27.0) which collides with
    // the SEPARATE libonnxruntime.so (onnxruntime 1.14.0) shipped by :libs:rapidocr for RapidOCR.
    // Identical SONAME + incompatible C++ ABI across versions means pickFirsts would break one
    // consumer at runtime, so the bundled copy (and every DT_NEEDED reference to it) was renamed
    // to libonnxrt127.so via an equal-length byte patch of the .so files inside this AAR.
    // The untouched original is kept alongside as sherpa-onnx-1.13.4.aar.orig.
    files += "sherpa-onnx-1.13.4-renameort.aar"
}
