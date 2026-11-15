package org.jibro.collection.review.local;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;

/** Uses validated table coordinates, not proximity in flattened PDF text, to bind final-result cells. */
public final class IndividualAnnouncementTableReader {
    private static final Pattern HEADER = Pattern.compile("(?:예비입주자|입주대상자|당첨자|결과)(?:확정)?발표(?:\\(예비입주자\\))?");
    private static final Pattern VALUE = Pattern.compile("(?:적격자에\\s*한하여\\s*|자격검증\\s*및\\s*(?:부적격자\\s*)?소명\\s*완료\\s*후\\s*)?"
        + "개별\\s*(?:통보|안내)(?:\\s*\\(20\\d{2}\\s*[.년]\\s*\\d{1,2}\\s*월\\s*예정\\))?");
    public record Finding(String description, String location) {}
    private IndividualAnnouncementTableReader() {}

    public static List<Finding> read(Source source, Part part) {
        var reading = HwpxContextReader.read(source, part);
        if (!reading.valid()) return List.of();
        var results = new ArrayList<Finding>();
        for (var header : reading.segments()) {
            var context = header.context();
            if (context == null || context.cell() == null || !context.cell().known()) continue;
            String title = part.text().substring(header.start(), header.end()).strip();
            if (!HEADER.matcher(title.replaceAll("\\s+", "")).matches()) continue;
            var cell = context.cell();
            if (cell.columnSpan() != 1) continue;
            for (var value : reading.segments()) {
                var vc = value.context();
                if (vc == null || vc.cell() == null || !vc.cell().known()) continue;
                var other = vc.cell();
                if (other.table() != cell.table() || other.column() != cell.column() || other.columnSpan() != 1
                    || other.row() < cell.row() + cell.rowSpan()) continue;
                String description = part.text().substring(value.start(), value.end()).strip();
                if (VALUE.matcher(description).matches()) results.add(new Finding(title.replaceAll("\\s+", " ")
                    + ": " + description.replaceAll("\\s+", " "), part.location() + " · " + vc.description()));
            }
        }
        return List.copyOf(results);
    }
}
