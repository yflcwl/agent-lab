function escapeHtml(value: string): string {
    return value.replace(/[&<>"']/g, character => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        "\"": "&quot;",
        "'": "&#39;"
    }[character] || character));
}

function inlineMarkdown(value: string): string {
    return value
        .replace(/`([^`]+)`/g, "<code>$1</code>")
        .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
        .replace(/\*([^*]+)\*/g, "<em>$1</em>");
}

export function renderMarkdown(markdown: string): string {
    const lines = escapeHtml(markdown.replace(/\r\n/g, "\n")).split("\n");
    const output: string[] = [];
    let paragraph: string[] = [];
    let listType: "ul" | "ol" | null = null;
    let inCode = false;
    let code: string[] = [];

    const flushParagraph = () => {
        if (paragraph.length) output.push(`<p>${inlineMarkdown(paragraph.join(" "))}</p>`);
        paragraph = [];
    };
    const closeList = () => {
        if (listType) output.push(`</${listType}>`);
        listType = null;
    };

    for (const line of lines) {
        if (line.startsWith("```")) {
            flushParagraph();
            closeList();
            if (inCode) {
                output.push(`<pre><code>${code.join("\n")}</code></pre>`);
                code = [];
            }
            inCode = !inCode;
            continue;
        }
        if (inCode) {
            code.push(line);
            continue;
        }
        if (!line.trim()) {
            flushParagraph();
            closeList();
            continue;
        }
        const heading = line.match(/^(#{1,4})\s+(.+)$/);
        if (heading) {
            flushParagraph();
            closeList();
            const level = heading[1]?.length || 1;
            output.push(`<h${level}>${inlineMarkdown(heading[2] || "")}</h${level}>`);
            continue;
        }
        const unordered = line.match(/^[-*+]\s+(.+)$/);
        const ordered = line.match(/^\d+[.)]\s+(.+)$/);
        if (unordered || ordered) {
            flushParagraph();
            const nextType = unordered ? "ul" : "ol";
            if (listType !== nextType) {
                closeList();
                output.push(`<${nextType}>`);
                listType = nextType;
            }
            output.push(`<li>${inlineMarkdown(unordered?.[1] || ordered?.[1] || "")}</li>`);
            continue;
        }
        if (line.startsWith("&gt; ")) {
            flushParagraph();
            closeList();
            output.push(`<blockquote>${inlineMarkdown(line.slice(5))}</blockquote>`);
            continue;
        }
        if (/^---+$/.test(line.trim())) {
            flushParagraph();
            closeList();
            output.push("<hr>");
            continue;
        }
        paragraph.push(line.trim());
    }
    flushParagraph();
    closeList();
    if (code.length) output.push(`<pre><code>${code.join("\n")}</code></pre>`);
    return output.join("");
}
