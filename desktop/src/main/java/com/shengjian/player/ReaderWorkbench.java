package com.shengjian.player;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.FontUIResource;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/** Desktop tester for the same source import and reading paths used by Android. */
public final class ReaderWorkbench {
    private static final String BRAND = "ANON TOOL";
    private static final Color BACKGROUND = new Color(0xfff7fa);
    private static final Color PANEL = new Color(0xfffdfe);
    private static final Color BORDER = new Color(0xd8adbf);
    private static final Color TEXT = new Color(0x2d1d2b);
    private static final Color MUTED = new Color(0x6d5262);
    private static final Color ACCENT = new Color(0xa43b6e);
    private static final Color SELECTED = new Color(0xf9dce9);
    private static final Color ERROR = new Color(0x922348);

    private final ReaderSources catalog;
    private final JFrame frame = new JFrame(BRAND + " · 图源与书源验证");
    private final DefaultListModel<ReaderSources.Source> sourceModel = new DefaultListModel<>();
    private final DefaultListModel<ReaderEngine.Book> bookModel = new DefaultListModel<>();
    private final DefaultListModel<ReaderEngine.Chapter> chapterModel = new DefaultListModel<>();
    private final JList<ReaderSources.Source> sources = list(sourceModel, ReaderWorkbench::sourceLabel);
    private final JList<ReaderEngine.Book> books = list(bookModel, book -> join(book.title, book.author));
    private final JList<ReaderEngine.Chapter> chapters = list(chapterModel, chapter -> chapter.title);
    private final JToggleButton manga = new JToggleButton("漫画", true);
    private final JToggleButton novel = new JToggleButton("小说");
    private final JLabel sourceCount = label("来源", 15, TEXT);
    private final JLabel sourceDetails = label("请选择来源", 13, MUTED);
    private final JLabel bookCount = label("作品", 15, TEXT);
    private final JLabel chapterCount = label("章节", 15, TEXT);
    private final JLabel previewTitle = label("章节预览", 15, TEXT);
    private final JLabel status = label("导入来源，或载入示例开始验证。", 13, MUTED);
    private final JProgressBar working = new JProgressBar();
    private final JTextField keyword = new JTextField();
    private final JButton search = button("搜索", this::search);
    private final JButton discover = button("发现", this::discover);
    private int imageIndex = -1;
    private final JButton previous = button("上一张", () -> showImage(imageIndex - 1));
    private final JButton retry = button("重试图片", () -> showImage(imageIndex));
    private final JButton next = button("下一张", () -> showImage(imageIndex + 1));
    private final JLabel imagePosition = label("", 13, MUTED);
    private final JPanel imageControls = flow(FlowLayout.LEFT);
    private final JScrollPane preview = scroll(new JTextArea());

    private SwingWorker<?, ?> running;
    private ReaderEngine.Page readingPage;
    private ReaderEngine.Chapter readingChapter;
    private int kind = ReaderSources.COMIC;
    private boolean updating;

    private ReaderWorkbench(ReaderSources catalog) {
        this.catalog = catalog;
        buildWindow();
        showSources(null);
    }

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(BRAND + " 的来源验证需要桌面图形环境。");
            return;
        }
        String configuredCatalog = System.getProperty("shengjian.desktop.storage", "");
        Path catalogFile = configuredCatalog.isBlank()
                ? Path.of(System.getProperty("user.home"), ".shengjian", "workbench", "sources.json")
                : Path.of(configuredCatalog);
        ReaderSources catalog = new ReaderSources(catalogFile.toFile(), ReaderWorkbench::asset);
        SwingUtilities.invokeLater(() -> {
            applyTheme();
            new ReaderWorkbench(catalog).frame.setVisible(true);
        });
    }

    static InputStream asset(String path) throws IOException {
        String assetDirectory = System.getProperty("shengjian.assets", "");
        if (!assetDirectory.isBlank()) {
            Path root = Path.of(assetDirectory).toAbsolutePath().normalize();
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root)) throw new IOException("无效的示例文件路径");
            return Files.newInputStream(file);
        }
        InputStream bundled = ReaderWorkbench.class.getResourceAsStream("/" + path);
        if (bundled == null) throw new FileNotFoundException("找不到示例文件 " + path);
        return bundled;
    }

    private void buildWindow() {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(BACKGROUND);
        root.setBorder(new EmptyBorder(14, 16, 9, 16));
        root.add(toolbar(), BorderLayout.NORTH);
        JSplitPane reading = split(JSplitPane.HORIZONTAL_SPLIT, resultPanel(), previewPanel(), 0.38);
        JSplitPane workspace = split(JSplitPane.HORIZONTAL_SPLIT, sourcePanel(), reading, 0.25);
        root.add(workspace, BorderLayout.CENTER);
        root.add(statusBar(), BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1200, 765);
        frame.setMinimumSize(new Dimension(940, 570));
        frame.setLocationRelativeTo(null);

        sources.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !updating) sourceChanged();
        });
        books.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !updating) chooseBook();
        });
        chapters.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !updating) chooseChapter();
        });
        keyword.addActionListener(ignored -> search());
        SwingUtilities.invokeLater(() -> {
            workspace.setDividerLocation(0.234);
            reading.setDividerLocation(0.37);
        });
    }

    private JPanel toolbar() {
        JPanel bar = new JPanel(new BorderLayout(10, 8));
        bar.setOpaque(false);
        JLabel brand = label(BRAND + "  /  来源验证", 23, ACCENT);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD));
        bar.add(brand, BorderLayout.WEST);
        JPanel actions = flow(FlowLayout.RIGHT);
        ButtonGroup types = new ButtonGroup();
        for (JToggleButton tab : List.of(manga, novel)) {
            types.add(tab);
            actions.add(tab);
        }
        manga.addActionListener(ignored -> selectKind(ReaderSources.COMIC));
        novel.addActionListener(ignored -> selectKind(ReaderSources.NOVEL));
        actions.add(button("载入示例", this::importSample));
        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    private JPanel sourcePanel() {
        JPanel panel = panel();
        JPanel header = new JPanel(new BorderLayout(0, 7));
        header.setOpaque(false);
        header.add(sourceCount, BorderLayout.NORTH);
        JPanel importButtons = flow(FlowLayout.LEFT);
        importButtons.add(button("文件", this::importFile));
        importButtons.add(button("粘贴", this::importPaste));
        importButtons.add(button("链接", this::importUrl));
        header.add(importButtons, BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);
        panel.add(scroll(sources), BorderLayout.CENTER);
        panel.setMinimumSize(new Dimension(200, 230));
        sources.getAccessibleContext().setAccessibleName("来源列表");
        return panel;
    }

    private JPanel resultPanel() {
        JPanel panel = panel();
        JPanel header = new JPanel(new BorderLayout(0, 8));
        header.setOpaque(false);
        header.add(sourceDetails, BorderLayout.NORTH);
        JPanel searchRow = new JPanel(new BorderLayout(5, 0));
        searchRow.setOpaque(false);
        keyword.setToolTipText("输入作品名称或作者");
        keyword.getAccessibleContext().setAccessibleName("作品搜索关键词");
        keyword.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(3, 6, 3, 6)));
        searchRow.add(keyword, BorderLayout.CENTER);
        JPanel buttons = flow(FlowLayout.RIGHT);
        buttons.add(search);
        buttons.add(discover);
        searchRow.add(buttons, BorderLayout.EAST);
        header.add(searchRow, BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);
        JPanel bookBox = listPanel(bookCount, books, "作品搜索或发现结果");
        JPanel chapterBox = listPanel(chapterCount, chapters, "章节目录");
        panel.add(split(JSplitPane.VERTICAL_SPLIT, bookBox, chapterBox, 0.45), BorderLayout.CENTER);
        return panel;
    }

    private JPanel previewPanel() {
        JPanel panel = panel();
        panel.add(previewTitle, BorderLayout.NORTH);
        panel.add(preview, BorderLayout.CENTER);
        imageControls.add(previous);
        imageControls.add(imagePosition);
        imageControls.add(next);
        imageControls.add(retry);
        imageControls.setVisible(false);
        panel.add(imageControls, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel statusBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(2, 3, 0, 3));
        bar.add(status, BorderLayout.CENTER);
        working.setPreferredSize(new Dimension(92, 9));
        working.setIndeterminate(false);
        working.setVisible(false);
        bar.add(working, BorderLayout.EAST);
        return bar;
    }

    private void selectKind(int selected) {
        if (selected == kind) return;
        kind = selected;
        showSources(null);
    }

    private void showSources(String selectedId) {
        updating = true;
        sourceModel.clear();
        sourceModel.addAll(catalog.list(kind));
        int index = 0;
        for (int i = 0; i < sourceModel.size(); i++) {
            if (sourceModel.get(i).id.equals(selectedId)) { index = i; break; }
        }
        if (!sourceModel.isEmpty()) sources.setSelectedIndex(index);
        sourceCount.setText((kind == ReaderSources.COMIC ? "漫画源" : "小说书源") + "  ·  " + sourceModel.size());
        updating = false;
        sourceChanged();
    }

    private void sourceChanged() {
        cancelWork();
        clearResults(true);
        ReaderSources.Source source = sources.getSelectedValue();
        boolean canSearch = source != null && !source.searchUrl.isBlank();
        boolean canDiscover = source != null && !source.exploreUrl.isBlank();
        keyword.setEnabled(canSearch);
        search.setEnabled(canSearch);
        discover.setEnabled(canDiscover);
        sourceDetails.setText(source == null ? "请选择来源" : source.name);
        sourceDetails.setToolTipText(source == null ? null : source.url);
        if (source == null) {
            showPreviewText("章节预览", "导入图源或书源后即可开始验证。");
            setStatus("导入文件、粘贴 JSON、粘贴链接，或载入离线示例。", false);
            return;
        }
        String actions = canSearch && canDiscover ? "输入关键词搜索，或点「发现」选择栏目。"
                : canSearch ? "输入作品名或作者，搜索后选中一部作品。"
                : canDiscover ? "点「发现」选择栏目，找到作品后读取目录。" : "此来源没有可用的搜索或发现入口。";
        showPreviewText("章节预览", actions);
        setStatus("已选来源  ·  " + source.name, false);
    }

    private void clearResults(boolean clearBooks) {
        updating = true;
        if (clearBooks) {
            bookModel.clear();
            bookCount.setText("作品");
        }
        chapterModel.clear();
        chapterCount.setText("章节");
        updating = false;
        readingPage = null;
        readingChapter = null;
        imageIndex = -1;
        imageControls.setVisible(false);
    }

    private void importFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择图源或书源 JSON 文件");
        chooser.setFileFilter(new FileNameExtensionFilter("来源文件 (*.json, *.txt, *.zip, *.gz)",
                "json", "txt", "zip", "gz"));
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File selectedFile = chooser.getSelectedFile();
            prepareImport(() -> catalog.prepareFile(selectedFile, ReaderSources.ALL));
        }
    }

    private void importPaste() {
        JTextArea input = new JTextArea(12, 55);
        input.getAccessibleContext().setAccessibleName("来源 JSON");
        if (JOptionPane.showConfirmDialog(frame, scroll(input), "粘贴图源或书源 JSON",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            String json = input.getText().trim();
            if (json.isEmpty()) { setStatus("请粘贴来源 JSON 后重试。", true); return; }
            prepareImport(() -> catalog.prepareText(json, ReaderSources.ALL));
        }
    }

    private void importUrl() {
        String input = JOptionPane.showInputDialog(frame, "粘贴来源 JSON 链接或异次元图源详情页。",
                "从链接导入", JOptionPane.PLAIN_MESSAGE);
        if (input == null) return;
        String link = input.trim();
        if (link.isEmpty()) { setStatus("请输入链接。", true); return; }
        prepareImport(() -> catalog.prepareUrl(link, ReaderSources.ALL));
    }

    private void importSample() {
        int selectedKind = kind;
        String path = selectedKind == ReaderSources.COMIC
                ? "reader-samples/comic-source.json" : "reader-samples/novel-source.json";
        prepareImport(() -> {
            try (InputStream file = asset(path)) {
                return catalog.prepareStream(file, selectedKind);
            }
        });
    }

    private void prepareImport(Callable<ReaderSources.ImportPlan> original) {
        work("解析来源", original, this::reviewImport);
    }

    private void reviewImport(ReaderSources.ImportPlan plan) {
        JTextArea report = readingArea(importReport(plan));
        report.setRows(15);
        report.setColumns(56);
        JScrollPane pane = scroll(report);
        pane.setPreferredSize(new Dimension(620, 330));
        if (plan.sources.isEmpty()) {
            JOptionPane.showMessageDialog(frame, pane, "来源验证结果", JOptionPane.WARNING_MESSAGE);
            setStatus("没有可保存的来源。", true);
            return;
        }
        String[] options = {"保存来源", "取消"};
        int confirmed = JOptionPane.showOptionDialog(frame, pane, "来源验证结果",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (confirmed != 0) { setStatus("已取消保存来源。", false); return; }
        work("保存来源", () -> catalog.apply(plan), result -> {
            ReaderSources.Source first = result.sources.stream().filter(source -> source.kind == kind)
                    .findFirst().orElse(result.sources.get(0));
            kind = first.kind;
            manga.setSelected(kind == ReaderSources.COMIC);
            novel.setSelected(kind == ReaderSources.NOVEL);
            showSources(first.id);
            setStatus("已保存来源  ·  " + summary(result), false);
        });
    }

    private static String importReport(ReaderSources.ImportResult result) {
        StringBuilder report = new StringBuilder(summary(result));
        for (String reason : result.diagnostics) report.append('\n').append(reason);
        if (!result.sources.isEmpty()) report.append("\n\n将保存以下来源\n");
        for (ReaderSources.Source source : result.sources) {
            report.append("  ").append(sourceLabel(source)).append('\n');
        }
        return report.toString();
    }

    private static String summary(ReaderSources.ImportResult result) {
        return "新增 " + result.added + " 个，更新 " + result.updated + " 个，跳过 " + result.skipped + " 个";
    }

    private void search() {
        ReaderSources.Source source = sources.getSelectedValue();
        if (source == null || source.searchUrl.isBlank()) return;
        String key = keyword.getText().trim();
        if (key.isEmpty()) { setStatus("输入作品名或作者后搜索。", true); return; }
        runResults("搜索", () -> ReaderEngine.search(source, key, 1));
    }

    private void discover() {
        ReaderSources.Source source = sources.getSelectedValue();
        if (source == null || source.exploreUrl.isBlank()) return;
        try {
            List<ReaderEngine.Explore> entries = ReaderEngine.exploreEntries(source);
            if (entries.isEmpty()) { setStatus("此来源没有可读取的发现栏目。", true); return; }
            ExploreChoice[] choices = entries.stream().map(ExploreChoice::new).toArray(ExploreChoice[]::new);
            Object selected = choices[0];
            if (choices.length > 1) {
                selected = JOptionPane.showInputDialog(frame, "选择发现栏目", source.name,
                        JOptionPane.PLAIN_MESSAGE, null, choices, choices[0]);
            }
            if (selected instanceof ExploreChoice choice) {
                runResults("发现", () -> ReaderEngine.explore(source, choice.entry.url, 1));
            }
        } catch (IOException invalid) {
            showFailure("读取发现栏目", invalid);
        }
    }

    private record ExploreChoice(ReaderEngine.Explore entry) {
        @Override public String toString() { return entry.title.isBlank() ? entry.url : entry.title; }
    }

    private void runResults(String action, Callable<List<ReaderEngine.Book>> operation) {
        clearResults(true);
        showPreviewText("章节预览", "正在读取作品…");
        work(action, operation, found -> {
            bookModel.addAll(found);
            bookCount.setText("作品  ·  " + found.size());
            showPreviewText("章节预览", found.isEmpty() ? "没有找到作品。" : "选择一部作品读取章节目录。");
            setStatus(action + "完成  ·  " + found.size() + " 部作品", false);
        });
    }

    private void chooseBook() {
        cancelWork();
        clearResults(false);
        ReaderEngine.Book book = books.getSelectedValue();
        if (book == null) return;
        showPreviewText(join(book.title, book.author), "正在读取作品详情…");
        work("读取作品详情", () -> ReaderEngine.details(book), this::readContents);
    }

    private void readContents(ReaderEngine.Book book) {
        String heading = join(book.title, book.author);
        showPreviewText(heading, book.summary.isBlank() ? "正在读取章节目录…" : book.summary);
        work("读取目录", () -> ReaderEngine.chapters(book), loaded -> {
            chapterModel.addAll(loaded);
            chapterCount.setText("章节  ·  " + loaded.size());
            setStatus("目录读取完成  ·  " + loaded.size() + " 章", false);
            if (book.summary.isBlank()) {
                showPreviewText(heading, loaded.isEmpty() ? "没有找到章节。" : "选择一章预览正文或图片。");
            }
        });
    }

    private void chooseChapter() {
        cancelWork();
        readingPage = null;
        readingChapter = chapters.getSelectedValue();
        imageIndex = -1;
        imageControls.setVisible(false);
        ReaderEngine.Chapter chapter = readingChapter;
        if (chapter == null) return;
        showPreviewText(chapter.title, "正在读取章节…");
        work("读取章节", () -> ReaderEngine.open(chapter), opened -> {
            readingPage = opened;
            if (!opened.images.isEmpty()) {
                imageControls.setVisible(true);
                showImage(0);
                return;
            }
            showPreviewText(chapter.title, opened.text);
            setStatus("正文读取完成  ·  " + opened.text.length() + " 字符", false);
        });
    }

    private void showImage(int index) {
        ReaderEngine.Page page = readingPage;
        ReaderEngine.Chapter chapter = readingChapter;
        if (page == null || chapter == null || index < 0 || index >= page.images.size()) return;
        imageIndex = index;
        imagePosition.setText((index + 1) + " / " + page.images.size());
        previous.setEnabled(index > 0);
        next.setEnabled(index + 1 < page.images.size());
        retry.setEnabled(true);
        showPreviewText(chapter.title, "正在读取第 " + (index + 1) + " 张图片…");
        work("读取图片", () -> loadImage(page, chapter.book.source, index), image -> {
            preview.setViewportView(new MangaImage(image));
            preview.getVerticalScrollBar().setValue(0);
            setStatus("图片读取完成  ·  第 " + (index + 1) + " / " + page.images.size() + " 张", false);
        });
    }

    private <T> void work(String step, Callable<T> operation, Consumer<T> success) {
        cancelWork();
        working.setIndeterminate(true);
        working.setVisible(true);
        setStatus(step + "中…", false);
        SwingWorker<T, Void> task = new SwingWorker<>() {
            @Override protected T doInBackground() throws Exception { return operation.call(); }

            @Override protected void done() {
                if (running != this) return;
                running = null;
                working.setIndeterminate(false);
                working.setVisible(false);
                try {
                    success.accept(get());
                } catch (CancellationException ignored) {
                    setStatus("操作已取消。", false);
                } catch (InterruptedException interrupted) {
                    showFailure(step, interrupted);
                } catch (ExecutionException failed) {
                    showFailure(step, failed.getCause());
                } catch (RuntimeException failed) {
                    showFailure(step, failed);
                }
            }
        };
        running = task;
        task.execute();
    }

    private void cancelWork() {
        if (running == null) return;
        SwingWorker<?, ?> old = running;
        running = null;
        old.cancel(true);
        working.setIndeterminate(false);
        working.setVisible(false);
    }

    private void showFailure(String step, Throwable failure) {
        String error = failure.getMessage();
        if (error == null || error.isBlank()) error = failure.getClass().getSimpleName();
        showPreviewText(step + "失败", error);
        setStatus(step + "失败  ·  " + error, true);
    }

    private void showPreviewText(String title, String body) {
        previewTitle.setText(title);
        previewTitle.setToolTipText(title);
        preview.setViewportView(readingArea(body));
        preview.getVerticalScrollBar().setValue(0);
    }

    private void setStatus(String message, boolean error) {
        status.setText(message);
        status.setForeground(error ? ERROR : MUTED);
        status.setToolTipText(message);
    }

    private static BufferedImage loadImage(ReaderEngine.Page page, ReaderSources.Source source, int index)
            throws IOException {
        String request = page.images.get(index);
        String location = ReaderEngine.imageAddress(request);
        if (location.isBlank()) throw new IOException("图片地址为空。");
        byte[] bytes = ReaderSources.fetchImage(location, imageHeaders(source, page.url, location,
                ReaderEngine.imageRequestHeaders(request)));
        try (ImageInputStream data = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (data == null) throw new IOException("无法读取图片数据。");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(data);
            if (!readers.hasNext()) throw new IOException("已下载图片，但无法识别图片格式。");
            ImageReader codec = readers.next();
            try {
                codec.setInput(data, true, true);
                int width = codec.getWidth(0);
                int height = codec.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 60_000_000L) {
                    throw new IOException("原图尺寸超出桌面预览范围。");
                }
                int stride = 1;
                while ((long) (width / stride) * (height / stride) > 8_000_000L
                        || width / stride > 1800 || height / stride > 16000) stride++;
                ImageReadParam parameters = codec.getDefaultReadParam();
                if (stride > 1) parameters.setSourceSubsampling(stride, stride, 0, 0);
                return codec.read(0, parameters);
            } finally {
                codec.dispose();
            }
        }
    }

    private static Map<String, String> imageHeaders(ReaderSources.Source source, String referer,
                                                     String image, Map<String, String> inlineHeaders) {
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        boolean sameSite = ReaderEngine.sameOrigin(source.url, image);
        copyHeaders(result, source.headers(), sameSite);
        org.json.JSONObject chapterHeaders = source.definition.optJSONObject("imageHeaders");
        if (chapterHeaders != null) {
            for (Iterator<String> keys = chapterHeaders.keys(); keys.hasNext();) {
                String key = keys.next();
                Object value = chapterHeaders.opt(key);
                if (value instanceof String || value instanceof Number) {
                    copyHeaders(result, Collections.singletonMap(key, value.toString()), sameSite);
                }
            }
        }
        copyHeaders(result, inlineHeaders, sameSite);
        if ("thisPage".equalsIgnoreCase(result.get("Referer")) || !result.containsKey("Referer")) {
            try {
                URI page = URI.create(referer);
                boolean full = result.containsKey("Referer") || ReaderEngine.sameOrigin(referer, image);
                if (full) {
                    int fragment = referer.indexOf('#');
                    result.put("Referer", fragment < 0 ? referer : referer.substring(0, fragment));
                } else {
                    URI origin = new URI(page.getScheme(), null, page.getHost(), page.getPort(), "/", null, null);
                    result.put("Referer", origin.toASCIIString());
                }
            } catch (IllegalArgumentException | URISyntaxException invalid) {
                result.remove("Referer");
            }
        }
        return result;
    }

    private static void copyHeaders(Map<String, String> output, Map<String, String> incoming, boolean sameSite) {
        for (Map.Entry<String, String> header : incoming.entrySet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (sameSite || name.equals("accept") || name.equals("accept-language")
                    || name.equals("referer") || name.equals("user-agent")) {
                output.put(header.getKey(), header.getValue());
            }
        }
    }

    private static final class MangaImage extends JComponent implements Scrollable {
        private static final long serialVersionUID = 1L;
        private final BufferedImage image;

        MangaImage(BufferedImage image) {
            this.image = image;
        }

        @Override public Dimension getPreferredSize() {
            int viewportWidth = getParent() == null ? 500 : Math.max(getParent().getWidth(), 1);
            int width = targetWidth(viewportWidth);
            int height = Math.max(1, (int) Math.round(width * (image.getHeight() / (double) image.getWidth())));
            return new Dimension(viewportWidth, height + 16);
        }

        private int targetWidth(int viewportWidth) {
            return Math.max(1, Math.min(image.getWidth(), viewportWidth - 16));
        }

        @Override protected void paintComponent(Graphics canvas) {
            super.paintComponent(canvas);
            Graphics2D graphics = (Graphics2D) canvas.create();
            try {
                int width = targetWidth(getWidth());
                int height = Math.max(1, (int) Math.round(width * (image.getHeight() / (double) image.getWidth())));
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(image, (getWidth() - width) / 2, 8, width, height, this);
            } finally {
                graphics.dispose();
            }
        }

        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(480, 600); }
        @Override public int getScrollableUnitIncrement(Rectangle viewport, int orientation, int direction) { return 40; }
        @Override public int getScrollableBlockIncrement(Rectangle viewport, int orientation, int direction) {
            return Math.max(40, (orientation == SwingConstants.VERTICAL ? viewport.height : viewport.width) - 40);
        }
    }

    private static String sourceLabel(ReaderSources.Source source) {
        String format = source.definition.optBoolean("_yiciyuan", false) ? "异次元"
                : source.kind == ReaderSources.COMIC ? "漫画" : "小说";
        return join(source.name, source.enabled ? format : format + " · 已停用");
    }

    private static String join(String first, String second) {
        return second == null || second.isBlank() ? first : first + "  ·  " + second;
    }

    private static JPanel panel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(12, 12, 12, 12)));
        return panel;
    }

    private static JPanel listPanel(JLabel title, JList<?> items, String description) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setOpaque(false);
        panel.add(title, BorderLayout.NORTH);
        panel.add(scroll(items), BorderLayout.CENTER);
        items.getAccessibleContext().setAccessibleName(description);
        return panel;
    }

    private static <T> JList<T> list(DefaultListModel<T> entries, Function<T, String> title) {
        JList<T> list = new JList<>(entries);
        list.setBackground(BACKGROUND);
        list.setForeground(TEXT);
        list.setSelectionBackground(SELECTED);
        list.setSelectionForeground(TEXT);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(32);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                setBorder(new EmptyBorder(0, 8, 0, 8));
                if (index >= 0 && index < entries.size()) setText(title.apply(entries.get(index)));
                return this;
            }
        });
        return list;
    }

    private static JScrollPane scroll(Component content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(25);
        return scroll;
    }

    private static JSplitPane split(int axis, Component first, Component second, double weight) {
        JSplitPane split = new JSplitPane(axis, true, first, second);
        split.setResizeWeight(weight);
        split.setDividerSize(8);
        split.setBorder(new EmptyBorder(0, 0, 0, 0));
        split.setBackground(BACKGROUND);
        return split;
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setMargin(new java.awt.Insets(4, 9, 4, 9));
        button.addActionListener(ignored -> action.run());
        return button;
    }

    private static JLabel label(String text, int size, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont((float) size));
        label.setForeground(color);
        return label;
    }

    private static JTextArea readingArea(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        area.setBackground(BACKGROUND);
        area.setForeground(TEXT);
        area.setFont(area.getFont().deriveFont(16f));
        area.setBorder(new EmptyBorder(15, 17, 15, 17));
        area.setCaretPosition(0);
        return area;
    }

    private static JPanel flow(int alignment) {
        JPanel flow = new JPanel(new FlowLayout(alignment, 6, 0));
        flow.setOpaque(false);
        return flow;
    }

    private static void applyTheme() {
        FontUIResource font = new FontUIResource(new Font("SansSerif", Font.PLAIN, 14));
        for (Object key : UIManager.getDefaults().keySet()) {
            if (UIManager.get(key) instanceof FontUIResource) UIManager.put(key, font);
        }
        for (String component : List.of("Panel", "OptionPane", "Label", "Button", "ToggleButton", "TextField",
                "TextArea", "List", "ScrollPane", "Viewport", "FileChooser", "ComboBox", "ToolTip")) {
            UIManager.put(component + ".background", PANEL);
            UIManager.put(component + ".foreground", TEXT);
        }
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("TextField.caretForeground", ACCENT);
        UIManager.put("TextArea.caretForeground", ACCENT);
        UIManager.put("TextField.selectionBackground", SELECTED);
        UIManager.put("TextArea.selectionBackground", SELECTED);
        UIManager.put("TextField.selectionForeground", TEXT);
        UIManager.put("TextArea.selectionForeground", TEXT);
        UIManager.put("ToggleButton.select", SELECTED);
        UIManager.put("Button.select", SELECTED);
        UIManager.put("ProgressBar.foreground", ACCENT);
        UIManager.put("ProgressBar.background", PANEL);
        UIManager.put("ScrollBar.thumb", BORDER);
        UIManager.put("ScrollBar.track", BACKGROUND);
        UIManager.put("SplitPaneDivider.draggingColor", ACCENT);
    }
}
