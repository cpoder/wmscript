package com.softwareag.is.wmscript.editor;

import com.softwareag.is.core.iscomm.server.IServerConnection;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.IContentAssistant;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;

/**
 * SourceViewer configuration for WmScript editor.
 * Provides content assist (autocompletion).
 */
public class WmScriptSourceViewerConfiguration extends SourceViewerConfiguration {

    private final IServerConnection server;
    private final String currentServicePath;

    public WmScriptSourceViewerConfiguration(IServerConnection server, String currentServicePath) {
        this.server = server;
        this.currentServicePath = currentServicePath;
    }

    @Override
    public IContentAssistant getContentAssistant(ISourceViewer sourceViewer) {
        ContentAssistant assistant = new ContentAssistant();

        WmScriptContentAssistProcessor processor =
            new WmScriptContentAssistProcessor(server, currentServicePath);
        assistant.setContentAssistProcessor(processor, IDocument.DEFAULT_CONTENT_TYPE);

        assistant.enableAutoActivation(true);
        assistant.setAutoActivationDelay(300);
        assistant.setProposalPopupOrientation(IContentAssistant.PROPOSAL_OVERLAY);
        assistant.enableAutoInsert(true);

        return assistant;
    }
}
