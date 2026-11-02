package com.buzzerbeater.seenario.camera

/**
 * 카메라에서 뽑아낸 프레임 1장. 이미 회전 보정·축소·JPEG 인코딩이 끝난 상태다.
 * 메모리에만 존재하며 디스크에 저장하지 않는다(사진 비저장 원칙).
 */
class CameraFrame(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
) {
    val mimeType: String get() = "image/jpeg"
}
