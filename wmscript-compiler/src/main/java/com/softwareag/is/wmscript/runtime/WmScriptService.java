package com.softwareag.is.wmscript.runtime;

import com.wm.app.b2b.server.BaseService;
import com.wm.app.b2b.server.Package;
import com.wm.app.b2b.server.ServerClassLoader;
import com.wm.app.b2b.server.ServiceSetupException;
import com.wm.data.IData;
import com.wm.lang.ns.NSName;
import com.wm.lang.ns.NSServiceType;
import com.wm.util.Values;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * IS service type for WmScript.
 * Loads pre-compiled .class files produced by the WmScript compiler (run outside IS).
 * Execution model is identical to JavaService: reflection-based static method invocation.
 *
 * Compilation happens externally (VS Code plugin or CLI):
 *   service.wms -> WmScript compiler -> .java -> javac -> .class
 *
 * The .class files must be in code/classes/wmscript/generated/ before the service is invoked.
 */
public class WmScriptService extends BaseService {

    private static final NSServiceType SVC_TYPE =
            NSServiceType.create("wmscript", "default");

    /** Must match WmScriptCompiler.GENERATED_PACKAGE */
    private static final String GENERATED_PACKAGE = "wmscript.generated";

    // Resolved compiled method
    private Class<?> compiledClass;
    private Method compiledMethod;
    private ServerClassLoader loader;
    private boolean valid = false;

    public WmScriptService(Package pkg, NSName name, Values nodeDef) {
        super(pkg, name, SVC_TYPE);
        if (nodeDef != null) {
            this.setNodeValues(nodeDef);
        }
    }

    @Override
    public void initializeService() {
        try {
            if (!valid) {
                validate();
            }
        } catch (Exception e) {
            // Log but don't throw (IS convention — service will fail on first invoke)
            com.wm.app.b2b.server.Server.logError(e);
        }
    }

    /**
     * Load the pre-compiled class from code/classes/.
     * Uses the same class name encoding as WmScriptCompiler.toClassName().
     */
    public void validate() throws ServiceSetupException {
        try {
            this.loader = ServerClassLoader.getPackageLoader(this.getPackageName());
            String className = toClassName(this.getNSName().getFullName());
            String fqClassName = GENERATED_PACKAGE + "." + className;

            try {
                this.compiledClass = this.loader.loadClass(fqClassName);
            } catch (ClassNotFoundException e) {
                throw new ServiceSetupException(
                        "Compiled WmScript class not found: " + fqClassName + ". "
                        + "Run the WmScript compiler to generate .class files. "
                        + "Expected in: code/classes/" + GENERATED_PACKAGE.replace('.', '/') + "/" + className + ".class");
            }

            this.compiledMethod = this.compiledClass.getMethod("main", IData.class);

            if (!Modifier.isStatic(this.compiledMethod.getModifiers())) {
                throw new ServiceSetupException(
                        "Generated WmScript class " + fqClassName + " has non-static main method");
            }

            this.valid = true;

        } catch (ServiceSetupException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceSetupException("WmScript validation failed: " + e.getMessage());
        }
    }

    @Override
    public IData baseInvoke(IData pipeline) throws Exception {
        if (!valid || (loader != null && loader.isDefunct())) {
            validate();
        }

        try {
            compiledMethod.invoke(null, pipeline);
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target instanceof Exception) {
                throw (Exception) target;
            }
            throw new RuntimeException(target);
        }

        return pipeline;
    }

    @Override
    public Values baseInvoke(Values pipeline) throws Exception {
        IData result = baseInvoke((IData) pipeline);
        return (result instanceof Values) ? (Values) result : Values.use(result);
    }

    /**
     * Encode IS namespace name as Java class name.
     * Must match WmScriptCompiler.toClassName().
     */
    static String toClassName(String nsName) {
        StringBuilder sb = new StringBuilder(nsName.length() + 10);
        for (char c : nsName.toCharArray()) {
            switch (c) {
                case '.': sb.append("_0"); break;
                case ':': sb.append("_1"); break;
                case '_': sb.append("_2"); break;
                default:  sb.append(c);
            }
        }
        return sb.toString();
    }
}
