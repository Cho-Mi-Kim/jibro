package org.jibro.collection.review;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;

/**
 * Applicability guard for the reviewed relaxed-rental draft, not an eligibility decision.
 * Each paraphrase must still be supported in its own section. Repeated keywords in appendices,
 * renewal rules or a different row cannot substitute for a changed application requirement.
 */
public final class NationalRentalTemplateScope {
    private NationalRentalTemplateScope() {
    }

    private static final Pattern PAGE_COUNTER = Pattern.compile("(?m)^\\s*-\\s*\\d+\\s*-\\s*$");
    private static final Pattern SUBLEASE_YEARS = Pattern.compile("불법양도·전대행위로적발된후(\\d+)년이경과되지않은자가있는경우");
    private static final String ADULT = """
            「민법」상 미성년자(19세 미만)는 공급 신청할 수 없습니다. 단, 아래의 어느 하나에 해당하는 경우 미성년자도 공급
            신청 가능합니다.
            - 자녀가 있는 미성년 세대주(단, 동일한 세대별 주민등록표에 자녀가 등재되어야 함)
            - 직계존속의 사망, 실종, 행방불명 등으로 형제자매를 부양하여야 하는 미성년 세대주 (단, 행방불명의 경우 신고
            접수증으로 증빙해야하며, 부양해야하는 형제자매는 동일한 세대별 주민등록표상에 등재되어있어야 함)
            - 외국인 부모와 미성년 자녀(내국인) 세대주로 구성된 한부모가족인 경우(단, 이 경우 외국인 부모가 대리하여 신청)
            """;
    private static final String SUBLEASE = """
            불법양도・전대자 재입주 금지 규정(공공주택 특별법 시행규칙 제36조의2)에 따라 신청자의 세대구성원중에 과거에 공공
            임대주택 임차인으로서 불법양도・전대 행위로 적발된 후 4년이 경과되지 않은 자가 있는 경우 신청자는 공공임대주택
            입주자로 선정 불가)
            """;
    private static final String VISIT_DOCUMENTS = """
            구 분 구 비 서 류
            ① 본인 신분증(주민등록증, 운전면허증, 여권만 인정)
            본인 직접 신청할 때
            ② 본인 도장(서명으로 대체 가능)
            ① 본인(신청자) 및 배우자의 신분증(주민등록증, 운전면허증, 여권만 인정)
            배우자가 대리 신청할 때 ② 본인(신청자) 도장
            ③ 본인(신청자)과의 관계 입증서류(주민등록표등본 등)
            ① (인감증명방식) 본인(신청자)의 인감증명서, 위임장(인감증명서상의 도장일 것),
            본인(신청자)의 인감도장 * 대리발급한 신청자의 인감증명서로 신청은 가능함
            ② (본인서명방식) 본인(신청자)의 본인서명사실확인서, 본인(신청자)이 자필
            기타 대리인이 대리 신청할 때
            서명한 위임장(본인서명사실확인서상의 서명일 것),
            * 수임인 서명란에 대리인 성명을 반드시 명기
            ③ 공통 : 본인(신청자) 및 대리인의 신분증(주민등록증, 운전면허증, 여권만 인정)
            ※ 2020.12.21.이후 발급한 여권은 여권정보증명서를 함께 구비해야 신분증으로 활용할 수 있습니다.
            """;

    public static boolean matches(Source source) {
        if (source == null || !"EXTRACTED".equals(source.status()) || source.context().isEmpty()
                || source.context().size() > 200 || source.context().stream().anyMatch(p -> p == null || p.text() == null)
                || source.context().stream().mapToLong(p -> p.text().length()).sum() > 1_000_000) return false;
        String raw = source.context().stream().map(Part::text).reduce("", (a, b) -> a + "\n" + b).replace('\r', '\n');
        raw = PAGE_COUNTER.matcher(raw).replaceAll("");
        String eligibility = compact(section(raw, "4\\.\\s*신청자격", "5\\.\\s*선정기준"));
        String selection = compact(section(raw, "5\\.\\s*선정기준", "6\\.\\s*모집일정"));
        String documents = compact(section(raw, "8\\.\\s*제출서류", "9\\.\\s*계약안내"));
        if (eligibility.isEmpty() || selection.isEmpty() || documents.isEmpty()) return false;

        if (!between(eligibility, "■성년자", "■무주택세대구성원").equals(compact(ADULT))
                || !between(eligibility, "■불법전대자입주자격제한", "■소득및자산보유기준").equals(compact(SUBLEASE))
                || !between(eligibility, "▪외국인은", "▪예비신혼부부의경우").equals("신청불가합니다.")) return false;
        var years = SUBLEASE_YEARS.matcher(eligibility);
        int subleaseStatements = 0;
        while (years.find()) {
            if (!years.group(1).equals("4")) return false;
            subleaseStatements++;
        }
        if (subleaseStatements != 2) return false;

        if (!containsAll(eligibility, List.of(
                "신청자와주민등록상세대분리되어있는배우자(이하",
                "‘분리배우자’)포함", "태아세대구성원에포함되나자격검증예외",
                "신청자본인이입증서류를제출하여야합니다.", "실종선고절차가진행중인사람",
                "공급신청자와생계및주거를달리한다고시장·군수·구청장이확인한사람",
                "신청자와예비배우자,②신청자및예비배우자와동일한세대별주민등록표에등재될예정인세대원을포함",
                "신청이후에는변경이불가합니다.",
                "임차인의배우자는세대분리하더라도중복입주로서본임대주택입주가불가합니다.",
                "소형·저가주택*은무주택으로간주하고", "연접지역에주택이없을경우허용"))) return false;

        String vehicle = tail(eligibility, "총자산가액으로포함되는자동차액과별도로");
        if (!containsAll(vehicle, List.of(
                "비영업용승용자동차에한하여산출하고,해당세대가2대이상의자동차를소유한경우에는각각의자동차가액중높은차량가액을기준으로하며아래의경우를제외함.",
                "「장애인복지법」제39조에따른장애인사용자동차",
                "국가유공자로서상이등급1급내지7급에해당하는자의보철용차량",
                "저공해자동차의경우자동차가액에서정부와지방자치단체의보조금을제외한금액으로한다."))) return false;
        if (!containsAll(selection, List.of(
                "거주지역,순위및배점등에상관없이신청접수순번대로입주자를선정하되",
                "적격자에한하여개별적으로계약체결을통보합니다."))) return false;

        if (!tail(documents, "■현장방문신청자구비(제출)서류").equals(compact(VISIT_DOCUMENTS))) return false;
        if (!containsAll(documents, List.of(
                "국민임대주택•(제출대상)현장방문신청자공급신청서",
                "세대구성원전원(예비신혼부부의경우혼인으로구성될세대의세대원)이서명",
                "14세미만의세대구성원은보호자(법정대리인)가서명",
                "입주자확인서※(주의)동의서를제출하지않을경우입주자모집신청·접수가거부됨",
                "내용을확인하고신청자가중복선정서명또는날인(예비신혼부부의경우대표신청자)",
                "‘입주자모집공고일현재의세대주’와주민등록표등본이",
                "분리된공급신청자는‘입주자모집공고일현재의세대주'의주민등록표등본1통추가제출",
                "예비신혼부부의경우신청자및예비배우자2인모두각각제출",
                "주민등록표등본에서배우자확인이가능한경우제외",
                "주민등록표등본상미성년자녀를확인할수없는경우필수제출",
                "이혼·재혼의경우신청자와동일한주민등록등본에등재된자녀만인정",
                "(제출대상)신청자와주민등록표가분리된신청자의배우자",
                "신청확인서및세대구성확인서(공사양식)", "예비배우자신분증사본",
                "외국인등록사실증명서관리•배우자가외국인인경우사무소"))) return false;
        String financial = between(compact(raw), "▪금융자산조회안내", "※분양권");
        return containsAll(financial, List.of(
                "국민임대주택입주신청자의세대구성원전원은금융자산을포함한총자산가액을조회하기위하여입주자신청시‘금융정보등제공동의서’를반드시제출하여야하며,제출하지아니할경우신청이불가합니다."));
    }

    private static String section(String text, String start, String end) {
        var opening = Pattern.compile("(?m)^\\h*" + start + "[^\\n]*\\n").matcher(text);
        if (!opening.find()) return "";
        int from = opening.end();
        var closing = Pattern.compile("(?m)^\\h*" + end + "[^\\n]*\\n").matcher(text);
        if (!closing.find(from)) return "";
        return text.substring(from, closing.start());
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = from < 0 ? -1 : text.indexOf(end, from + start.length());
        return from < 0 || to < 0 ? "" : text.substring(from + start.length(), to);
    }

    private static String tail(String text, String start) {
        int from = text.indexOf(start);
        return from < 0 ? "" : text.substring(from + start.length());
    }

    private static boolean containsAll(String text, List<String> clauses) {
        return clauses.stream().allMatch(clause -> text.contains(compact(clause)));
    }

    private static String compact(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("[\\s\\p{Zs}]+", "").replaceAll("[・･‧]", "·");
    }
}
