package org.jibro.global.common;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public ApiException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "요청한 정보를 찾을 수 없습니다.");
    }
    public static ApiException conflict() {
        return new ApiException(409, "VERSION_CONFLICT", "기준이 변경되었습니다. 새로 조회한 후 확인해 주세요.");
    }
}
