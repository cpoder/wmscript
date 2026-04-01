package com.softwareag.is.wmscript.editor;

import com.softwareag.is.ui.navigator.model.ISServiceNode;
import com.softwareag.is.core.iscomm.server.IServerConnection;
import com.wm.lang.ns.NSNode;

/**
 * Navigator model node for WmScript services.
 * EditorUtils maps this class name to the WmScript editor via contentTypeClass.
 */
public class ISWmScriptServiceNode extends ISServiceNode {

    public ISWmScriptServiceNode(String name, NSNode nsNode, IServerConnection server) {
        super(name, nsNode, server);
        this.setType("WmScript Service");
    }
}
