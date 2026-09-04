package com.example.solution.application;

import com.example.solution.domain.TemplateSection;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarkdownTemplateParser {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    public List<TemplateSection> parse(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            throw new IllegalArgumentException("Markdown 模板不能为空");
        }
        List<Heading> headings = collectHeadings(markdown);
        if (headings.isEmpty()) {
            throw new IllegalArgumentException("Markdown 模板至少需要一个章节标题");
        }
        List<TemplateSection> result = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            int endLine = markdown.lines().toList().size();
            for (int j = i + 1; j < headings.size(); j++) {
                if (headings.get(j).level() <= heading.level()) {
                    endLine = headings.get(j).line();
                    break;
                }
            }
            String requirement = lines(markdown, heading.line() + 1, endLine);
            result.add(new TemplateSection("heading-" + (i + 1), heading.level(), heading.title(), requirement));
        }
        return result;
    }

    private List<Heading> collectHeadings(String markdown) {
        List<String> lines = markdown.lines().toList();
        List<Heading> headings = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = HEADING.matcher(lines.get(i));
            if (matcher.matches()) {
                headings.add(new Heading(matcher.group(1).length(), matcher.group(2).trim(), i));
            }
        }
        return headings;
    }

    private String lines(String markdown, int start, int end) {
        List<String> allLines = markdown.lines().toList();
        return String.join("\n", allLines.subList(Math.min(start, allLines.size()), Math.min(end, allLines.size()))).trim();
    }

    private record Heading(int level, String title, int line) {
    }
}
