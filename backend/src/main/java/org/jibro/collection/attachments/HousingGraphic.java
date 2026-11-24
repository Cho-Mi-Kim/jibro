package org.jibro.collection.attachments;

import java.util.regex.Pattern;

/**
 * A separately named LH brochure or floor plan can be fingerprinted without treating it as application instructions.
 */
public final class HousingGraphic {
    private static final Pattern NAME = Pattern.compile("^(?!.*(?:정정|자격|제출|변경|일정|모집공고)).*(?:[_ ](?:팜플렛|팸플릿)\\.pdf|평면도(?:및동호배치도)?\\.zip)$");

    private HousingGraphic() {
    }

    public static boolean matches(String label, String url) {
        return label != null && url != null && url.matches("https://apply\\.lh\\.or\\.kr/lhapply/lhFile\\.do\\?fileid=[0-9]{1,20}")
            && NAME.matcher(label.strip()).matches();
    }
}

