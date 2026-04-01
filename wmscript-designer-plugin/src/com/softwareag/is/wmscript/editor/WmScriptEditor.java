package com.softwareag.is.wmscript.editor;

import com.softwareag.is.ui.IAssetChangeListener;
import com.softwareag.is.ui.editor.model.IModelElement;
import com.softwareag.is.ui.navigator.input.ISAssetEditorInput;
import com.softwareag.is.ui.serviceeditor.MultipageServiceEditor;
import com.wm.lang.ns.NSNode;
import com.wm.lang.ns.NSRecord;
import com.wm.lang.ns.NSService;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.VerticalRuler;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * WmScript multi-page editor for Designer.
 * Source tab shows .wms content fetched from IS with save support.
 */
public class WmScriptEditor extends MultipageServiceEditor
        implements IAssetChangeListener {

    private SourceViewer sourceViewer;
    private boolean dirty = false;
    private String servicePath;
    private com.softwareag.is.core.iscomm.server.IServerConnection server;

    // Semantic coloring
    private Color inputColor;
    private Color outputColor;
    private List<String> inputFieldNames = new ArrayList<>();
    private List<String> outputFieldNames = new ArrayList<>();
    private Runnable pendingColoring;

    public WmScriptEditor() {
        super(false);
    }

    @Override
    protected void configurePaletteRoot() {
    }

    @Override
    protected void createPages() {
        boolean valid = this.isValidToOpen();
        if (valid) {
            createSourcePage();
        }

        IEditorInput editorInput = this.getEditorInput();
        NSNode nsNode;
        if (editorInput instanceof ISAssetEditorInput
                && (nsNode = ((ISAssetEditorInput) editorInput).getAsset()) instanceof NSService
                && this.isListAllowed) {
            try {
                this.createIOPage();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        this.setActivePage(0, true);
        this.setContext();
    }

    private void createSourcePage() {
        try {
            Composite parent = new Composite(this.getContainer(), SWT.NONE);
            parent.setLayout(new FillLayout());

            // Create source viewer with line numbers
            VerticalRuler ruler = new VerticalRuler(40);
            sourceViewer = new SourceViewer(parent, ruler,
                    SWT.V_SCROLL | SWT.H_SCROLL | SWT.MULTI | SWT.BORDER);
            sourceViewer.setEditable(true);

            // Monospace font
            Font mono = new Font(Display.getCurrent(),
                    new FontData("Consolas", 11, SWT.NORMAL));
            sourceViewer.getTextWidget().setFont(mono);

            // Set up tm4e syntax highlighting
            try {
                org.eclipse.tm4e.ui.text.TMPresentationReconciler reconciler =
                        new org.eclipse.tm4e.ui.text.TMPresentationReconciler();
                reconciler.setGrammar(
                        org.eclipse.tm4e.registry.TMEclipseRegistryPlugin
                                .getGrammarRegistryManager()
                                .getGrammarForScope("source.wmscript"));
                reconciler.install(sourceViewer);
            } catch (Exception e) {
                System.err.println("WmScript: tm4e grammar not available: " + e.getMessage());
            }

            // Load source from IS and get server connection
            String source = "// Loading...";
            IEditorInput editorInput = this.getEditorInput();

            if (editorInput instanceof ISAssetEditorInput) {
                ISAssetEditorInput assetInput = (ISAssetEditorInput) editorInput;
                server = assetInput.getServer();
                try {
                    source = fetchSourceFromIS(assetInput);
                    NSNode asset = assetInput.getAsset();
                    if (asset != null) {
                        servicePath = asset.getNSName().getFullName();
                    }
                } catch (Exception e) {
                    source = "// Error loading source: " + e.getMessage();
                }
            }

            // Set up document with default partitioning
            IDocument document = new Document(source);
            org.eclipse.jface.text.IDocumentPartitioner partitioner =
                new org.eclipse.jface.text.rules.FastPartitioner(
                    new org.eclipse.jface.text.rules.RuleBasedPartitionScanner(),
                    new String[] { org.eclipse.jface.text.IDocument.DEFAULT_CONTENT_TYPE });
            partitioner.connect(document);
            document.setDocumentPartitioner(partitioner);
            sourceViewer.setDocument(document);

            // Install content assist
            final org.eclipse.jface.text.contentassist.ContentAssistant assistant =
                new org.eclipse.jface.text.contentassist.ContentAssistant();
            if (server != null && servicePath != null) {
                WmScriptContentAssistProcessor processor =
                    new WmScriptContentAssistProcessor(server, servicePath);
                assistant.setContentAssistProcessor(processor,
                    org.eclipse.jface.text.IDocument.DEFAULT_CONTENT_TYPE);
                assistant.enableAutoActivation(true);
                assistant.setAutoActivationDelay(300);
                assistant.setInformationControlCreator(
                    new org.eclipse.jface.text.AbstractReusableInformationControlCreator() {
                        protected org.eclipse.jface.text.IInformationControl doCreateInformationControl(
                                org.eclipse.swt.widgets.Shell shell) {
                            return new org.eclipse.jface.text.DefaultInformationControl(shell);
                        }
                    });
                assistant.install(sourceViewer);
            }

            // Bind Ctrl+Space to trigger content assist
            sourceViewer.getTextWidget().addKeyListener(new org.eclipse.swt.events.KeyAdapter() {
                @Override
                public void keyPressed(org.eclipse.swt.events.KeyEvent e) {
                    if (e.character == ' ' && (e.stateMask & SWT.CTRL) != 0) {
                        try {
                            assistant.showPossibleCompletions();
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                        e.doit = false;
                    }
                }
            });

            // Track changes for dirty state + trigger semantic coloring
            document.addDocumentListener(new IDocumentListener() {
                @Override
                public void documentAboutToBeChanged(DocumentEvent event) {}

                @Override
                public void documentChanged(DocumentEvent event) {
                    if (!dirty) {
                        dirty = true;
                        firePropertyChange(PROP_DIRTY);
                    }
                    scheduleColoring();
                }
            });

            // Semantic coloring: input fields blue, output fields green
            inputColor = new Color(Display.getCurrent(), 0, 100, 200);
            outputColor = new Color(Display.getCurrent(), 0, 160, 0);
            if (server != null && servicePath != null) {
                loadFieldNames();
            }
            Display.getCurrent().asyncExec(() -> applySemanticColoring());

            int pageIndex = this.addPage(parent);
            this.setPageText(pageIndex, "Source");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean isDirty() {
        return dirty || super.isDirty();
    }

    @Override
    public void doSave(IProgressMonitor monitor) {
        if (sourceViewer == null || servicePath == null) return;

        String source = sourceViewer.getDocument().get();
        IEditorInput editorInput = this.getEditorInput();

        if (editorInput instanceof ISAssetEditorInput) {
            try {
                ISAssetEditorInput assetInput = (ISAssetEditorInput) editorInput;
                com.softwareag.is.core.iscomm.server.IServerConnection server = assetInput.getServer();

                com.wm.data.IData input = com.wm.data.IDataFactory.create();
                com.wm.data.IDataCursor c = input.getCursor();
                com.wm.data.IDataUtil.put(c, "servicePath", servicePath);
                com.wm.data.IDataUtil.put(c, "source", source);
                c.destroy();

                com.wm.data.IData result = server.invoke(
                        com.wm.lang.ns.NSName.create("wmscript.admin:saveSource"), input);

                com.wm.data.IDataCursor rc = result.getCursor();
                String status = com.wm.data.IDataUtil.getString(rc, "status");
                String[] errors = com.wm.data.IDataUtil.getStringArray(rc, "errors");
                rc.destroy();

                if ("compiled".equals(status)) {
                    dirty = false;
                    firePropertyChange(PROP_DIRTY);
                } else if (errors != null && errors.length > 0) {
                    StringBuilder msg = new StringBuilder("WmScript compilation errors:\n");
                    for (String err : errors) {
                        msg.append(err).append("\n");
                    }
                    org.eclipse.jface.dialogs.MessageDialog.openError(
                            getSite().getShell(), "WmScript Compilation", msg.toString());
                }
            } catch (Exception e) {
                org.eclipse.jface.dialogs.MessageDialog.openError(
                        getSite().getShell(), "WmScript Save Error", e.getMessage());
            }
        }
    }

    @Override
    public boolean isSaveAsAllowed() {
        return false;
    }

    private String fetchSourceFromIS(ISAssetEditorInput assetInput) throws Exception {
        NSNode asset = assetInput.getAsset();
        if (asset == null) return "// No asset";

        String nsName = asset.getNSName().getFullName();
        com.softwareag.is.core.iscomm.server.IServerConnection server = assetInput.getServer();

        com.wm.data.IData input = com.wm.data.IDataFactory.create();
        com.wm.data.IDataCursor c = input.getCursor();
        com.wm.data.IDataUtil.put(c, "servicePath", nsName);
        c.destroy();

        com.wm.data.IData result = server.invoke(
                com.wm.lang.ns.NSName.create("wmscript.admin:getSource"), input);

        com.wm.data.IDataCursor rc = result.getCursor();
        String source = com.wm.data.IDataUtil.getString(rc, "source");
        rc.destroy();

        return source != null ? source : "// No source found";
    }

    // ================================================================
    // Semantic coloring
    // ================================================================

    private void loadFieldNames() {
        inputFieldNames.clear();
        outputFieldNames.clear();
        try {
            com.wm.data.IData input = com.wm.data.IDataFactory.create();
            com.wm.data.IDataCursor c = input.getCursor();
            com.wm.data.IDataUtil.put(c, "name", servicePath);
            c.destroy();

            com.wm.data.IData result = server.invoke(
                com.wm.lang.ns.NSName.create("wm.server.ns:getNode"), input);
            com.wm.data.IDataCursor rc = result.getCursor();
            Object nodeObj = com.wm.data.IDataUtil.get(rc, "node");
            rc.destroy();

            if (nodeObj instanceof com.wm.data.IData) {
                com.wm.data.IDataCursor nc = ((com.wm.data.IData) nodeObj).getCursor();
                Object sigObj = com.wm.data.IDataUtil.get(nc, "svc_sig");
                nc.destroy();
                if (sigObj instanceof com.wm.data.IData) {
                    com.wm.data.IDataCursor sc = ((com.wm.data.IData) sigObj).getCursor();
                    Object sigIn = com.wm.data.IDataUtil.get(sc, "sig_in");
                    Object sigOut = com.wm.data.IDataUtil.get(sc, "sig_out");
                    sc.destroy();
                    if (sigIn instanceof com.wm.data.IData)
                        extractFieldNames((com.wm.data.IData) sigIn, inputFieldNames);
                    if (sigOut instanceof com.wm.data.IData)
                        extractFieldNames((com.wm.data.IData) sigOut, outputFieldNames);
                }
            }
        } catch (Exception e) {
            // ignore — coloring is best-effort
        }
    }

    private void extractFieldNames(com.wm.data.IData sigRecord, List<String> target) {
        try {
            com.wm.data.IDataCursor c = sigRecord.getCursor();
            com.wm.data.IData[] fields = com.wm.data.IDataUtil.getIDataArray(c, "rec_fields");
            c.destroy();
            if (fields != null) {
                for (com.wm.data.IData field : fields) {
                    com.wm.data.IDataCursor fc = field.getCursor();
                    String name = com.wm.data.IDataUtil.getString(fc, "field_name");
                    fc.destroy();
                    if (name != null) target.add(name);
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void applySemanticColoring() {
        if (sourceViewer == null || sourceViewer.getTextWidget() == null
                || sourceViewer.getTextWidget().isDisposed()) return;
        if (inputFieldNames.isEmpty() && outputFieldNames.isEmpty()) return;

        StyledText st = sourceViewer.getTextWidget();
        String text = st.getText();

        // Apply output colors first, then input (input wins on overlap)
        for (String name : outputFieldNames) {
            applyColorToWord(st, text, name, outputColor);
        }
        for (String name : inputFieldNames) {
            applyColorToWord(st, text, name, inputColor);
        }
    }

    private void applyColorToWord(StyledText st, String text, String word, Color color) {
        int idx = 0;
        int wordLen = word.length();
        while ((idx = text.indexOf(word, idx)) >= 0) {
            boolean startOk = idx == 0 || !isIdentChar(text.charAt(idx - 1));
            boolean endOk = idx + wordLen >= text.length()
                    || !isIdentChar(text.charAt(idx + wordLen));
            if (startOk && endOk && !isInsideComment(text, idx)) {
                StyleRange range = new StyleRange();
                range.start = idx;
                range.length = wordLen;
                range.foreground = color;
                st.setStyleRange(range);
            }
            idx += wordLen;
        }
    }

    private boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private boolean isInsideComment(String text, int offset) {
        // Find the start of the line containing offset
        int lineStart = text.lastIndexOf('\n', offset - 1) + 1;
        String linePrefix = text.substring(lineStart, offset).trim();
        return linePrefix.startsWith("//") || linePrefix.startsWith("#");
    }

    private void scheduleColoring() {
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed()) return;
        if (pendingColoring != null) {
            display.timerExec(-1, pendingColoring);
        }
        pendingColoring = () -> applySemanticColoring();
        display.timerExec(500, pendingColoring);
    }

    // ================================================================

    @Override
    protected void createIOPage() throws Exception {
        super.createIOPage();
    }

    @Override
    public Object getModel() { return null; }

    @Override
    protected void getModelInStream(OutputStream out) throws Exception {}

    @Override
    protected void loadModel(IEditorInput input) throws Exception {
        if (input instanceof ISAssetEditorInput) {
            this.setPartName(((ISAssetEditorInput) input).getNodeName());
            this.setCurrentNsNode(((ISAssetEditorInput) input).getAsset());
        }
    }

    @Override
    protected void loadModelFromStream(InputStream in) throws Exception {}

    @Override
    public void assetChanged(NSRecord newIn, NSRecord newOut) {
        // I/O signature changed in the I/O tab — reload field names and re-color
        if (server != null && servicePath != null) {
            loadFieldNames();
            scheduleColoring();
        }
    }

    @Override
    public void assetChanged(IModelElement before, IModelElement after) {}

    @Override
    public void dispose() {
        if (inputColor != null && !inputColor.isDisposed()) inputColor.dispose();
        if (outputColor != null && !outputColor.isDisposed()) outputColor.dispose();
        super.dispose();
    }
}
