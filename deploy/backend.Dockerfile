ARG JAVA_BASE=eclipse-temurin:17-jre-jammy
FROM ${JAVA_BASE}
RUN apt-get update && apt-get install -y --no-install-recommends curl tesseract-ocr tesseract-ocr-kor tesseract-ocr-eng tesseract-ocr-osd \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 jibro && useradd --uid 10001 --gid jibro --no-create-home jibro
WORKDIR /opt/jibro
ENV JIBRO_OCR_EXECUTABLE=/usr/bin/tesseract \
    JIBRO_OCR_DATA_PATH=/usr/share/tesseract-ocr/4.00/tessdata
COPY --chown=10001:10001 backend/app.jar ./app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl --fail --silent --max-time 4 http://127.0.0.1:8080/api/v1/health > /dev/null || exit 1
ENTRYPOINT ["java", "-XX:ActiveProcessorCount=2", "-XX:MaxRAMPercentage=55", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/jibro/app.jar"]
