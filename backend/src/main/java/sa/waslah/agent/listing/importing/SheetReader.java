package sa.waslah.agent.listing.importing;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import sa.waslah.agent.common.ApiException;

/** Reads the first sheet of an .xlsx/.xls file, or a .csv file, into header + rows of strings. */
public final class SheetReader {

    public record Table(List<String> headers, List<List<String>> rows) {
    }

    public static final int MAX_ROWS = 2000;
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    private SheetReader() {
    }

    public static Table read(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("empty_file", "الملف فارغ.");
        }
        if (bytes.length > MAX_BYTES) {
            throw ApiException.badRequest("file_too_large", "حجم الملف أكبر من 5 ميجابايت.");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        Table t = name.endsWith(".csv") || name.endsWith(".txt") ? csv(bytes) : excel(bytes);
        if (t.headers().stream().allMatch(String::isBlank)) {
            throw ApiException.badRequest("no_headers", "أول صف في الملف يجب أن يحتوي عناوين الأعمدة.");
        }
        if (t.rows().isEmpty()) {
            throw ApiException.badRequest("no_rows", "لا توجد صفوف عقارات تحت العناوين.");
        }
        return t;
    }

    private static Table excel(byte[] bytes) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ROOT);
            List<List<String>> all = new ArrayList<>();
            int width = 0;
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                int last = Math.max(row.getLastCellNum(), 0);
                for (int c = 0; c < last; c++) {
                    cells.add(cellText(row.getCell(c), fmt));
                }
                if (cells.stream().allMatch(String::isBlank)) continue;
                width = Math.max(width, cells.size());
                all.add(cells);
                if (all.size() > MAX_ROWS + 1) {
                    throw ApiException.badRequest("too_many_rows", "الحد الأقصى " + MAX_ROWS + " عقار في الملف الواحد.");
                }
            }
            return split(all, width);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("unreadable_file", "تعذّرت قراءة الملف. ارفع ملف Excel (.xlsx) أو CSV.");
        }
    }

    private static String cellText(Cell cell, DataFormatter fmt) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (type == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
        }
        return fmt.formatCellValue(cell).trim();
    }

    static Table csv(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        char delim = guessDelimiter(text);
        List<List<String>> all = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == delim) {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().trim());
                cell.setLength(0);
                if (!row.stream().allMatch(String::isBlank)) {
                    width = Math.max(width, row.size());
                    all.add(row);
                }
                row = new ArrayList<>();
                if (all.size() > MAX_ROWS + 1) {
                    throw ApiException.badRequest("too_many_rows", "الحد الأقصى " + MAX_ROWS + " عقار في الملف الواحد.");
                }
            } else {
                cell.append(ch);
            }
        }
        row.add(cell.toString().trim());
        if (!row.stream().allMatch(String::isBlank)) {
            width = Math.max(width, row.size());
            all.add(row);
        }
        return split(all, width);
    }

    private static char guessDelimiter(String text) {
        int nl = text.indexOf('\n');
        String first = nl < 0 ? text : text.substring(0, nl);
        long commas = first.chars().filter(c -> c == ',').count();
        long semis = first.chars().filter(c -> c == ';').count();
        long tabs = first.chars().filter(c -> c == '\t').count();
        if (tabs > commas && tabs > semis) return '\t';
        return semis > commas ? ';' : ',';
    }

    private static Table split(List<List<String>> all, int width) {
        if (all.isEmpty()) return new Table(List.of(), List.of());
        List<List<String>> padded = new ArrayList<>();
        for (List<String> r : all) {
            List<String> p = new ArrayList<>(r);
            while (p.size() < width) p.add("");
            padded.add(p);
        }
        return new Table(padded.get(0), padded.subList(1, padded.size()));
    }
}
