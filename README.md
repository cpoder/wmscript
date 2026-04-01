# WmScript

A scripting language for [webMethods Integration Server](https://www.softwareag.com/en_corporate/platform/integration-apis/webmethods-integration.html). Write integration services in a clean, readable syntax instead of the Flow editor, with full access to the IS pipeline and service invocation.

WmScript compiles to Java bytecode and runs as a native IS service type — no interpreter overhead, full debuggability, and seamless interop with existing Flow and Java services.

```
// @input firstName : string
// @input lastName : string
// @output greeting : string

fullName = invoke pub.string:concat(inString1: firstName, inString2: " ")
{fullName} = invoke pub.string:concat(inString1: fullName, inString2: lastName)

if fullName == " " or fullName == "":
    greeting = "Hello, World!"
else:
    greeting = f"Hello, {fullName}!"
end
```

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Creating Services](#creating-services)
- [Language Reference](#language-reference)
- [Examples](#examples)
- [Building from Source](#building-from-source)
- [Project Structure](#project-structure)

## Features

- **Clean syntax** — Python-like blocks, named arguments, template strings
- **Native IS service type** — WmScript services appear alongside Flow and Java services
- **Full pipeline access** — Read/write pipeline variables, invoke any IS service
- **Compile-time safety** — Syntax errors caught before deployment
- **Designer integration** — Syntax highlighting, content assist, and I/O signature editing
- **30+ built-in functions** — String manipulation, array operations, type coercion, math
- **Null-safe operators** — `?.` and `??` for safe navigation and defaults
- **Array operations** — Projection (`orders[].id`), filtering (`orders[status == "active"]`)
- **Document literals** — Inline IData construction (`{key: "value", nested: {a: 1}}`)
- **Template strings** — String interpolation with expressions (`f"Hello, {name}!"`)
- **Source mapping** — Runtime errors map back to `.wms` line numbers
- **Zero runtime overhead** — Compiles to the same bytecode as hand-written Java services

## Architecture

```
                    +-----------------+
  service.wms  -->  |   ANTLR4 Parser |
                    +--------+--------+
                             |
                    +--------v--------+
                    | Java Code Gen   |  --> GeneratedClass.java
                    +--------+--------+
                             |
                    +--------v--------+
                    |     javac       |  --> GeneratedClass.class
                    +-----------------+

  At runtime:
    IS receives request
      --> WmScriptServiceFactory (registered via nsplugins.cnf)
        --> WmScriptService.baseInvoke()
          --> GeneratedClass.main(IData pipeline)  [via reflection]
            --> PipelineContext wraps pipeline
              --> reads inputs, invokes services, writes outputs
```

Three components work together:

| Component | Description |
|-----------|-------------|
| **wmscript-compiler** | ANTLR4 grammar, Java code generator, runtime library (PipelineContext), and IS admin services |
| **wmscript-package** | IS package that registers the `wmscript` service type and hosts compiled services |
| **wmscript-designer-plugin** | Eclipse plugin for Software AG Designer with syntax highlighting and content assist |

## Prerequisites

- webMethods Integration Server 10.7+ (Java 11 or later)
- Gradle 8.x (for building the compiler)
- JDK 11+ (for compilation; `javac` must be on `PATH` on the IS host)
- Software AG Designer 10.7+ (optional, for the Designer plugin)

## Installation

### 1. Build the Runtime JAR

```bash
cd wmscript-compiler
gradle runtimeJar
```

This produces `build/libs/wmscript-runtime-0.1.0.jar` — a fat JAR containing the compiler, runtime, ANTLR4, and IS admin services.

### 2. Deploy the IS Package

Copy the `wmscript-package` directory to your IS packages folder and add the runtime JAR:

```bash
# Copy the package
cp -r wmscript-package /path/to/IntegrationServer/instances/default/packages/WmScript

# Add the runtime JAR
cp wmscript-compiler/build/libs/wmscript-runtime-0.1.0.jar \
   /path/to/IntegrationServer/instances/default/packages/WmScript/code/jars/
```

Restart Integration Server (or reload the WmScript package).

The package registers the `wmscript` service type via `nsplugins.cnf` and exposes three admin services:

| Service | Description |
|---------|-------------|
| `wmscript.admin:getSource` | Read `.wms` source for a service |
| `wmscript.admin:saveSource` | Save `.wms` source and compile it on the server |
| `wmscript.admin:createService` | Create a new WmScript service (node.ndf + .wms + compile) |

### 3. Install the Designer Plugin (Optional)

Build and deploy the Eclipse plugin for Designer:

```bash
cd wmscript-designer-plugin

# Compile (adjust ECLIPSE_CP to your Designer's plugins directory)
ECLIPSE_CP="/path/to/Designer/eclipse/plugins/*"
javac --release 11 \
  -cp "$ECLIPSE_CP" \
  -d bin \
  src/com/softwareag/is/wmscript/editor/*.java

# Package as JAR
jar cf com.softwareag.is.wmscript.editor_0.1.0.jar \
  -C bin com \
  -C . plugin.xml \
  -C . META-INF \
  -C . grammars \
  -C . icons

# Deploy
cp com.softwareag.is.wmscript.editor_0.1.0.jar \
   /path/to/Designer/eclipse/plugins/

# Add to bundles.info
echo "com.softwareag.is.wmscript.editor,0.1.0,plugins/com.softwareag.is.wmscript.editor_0.1.0.jar,4,false" \
  >> /path/to/Designer/eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info
```

Restart Designer with the `-clean` flag for the first launch.

**Designer features:**
- Syntax highlighting via TextMate grammar
- Content assist (Ctrl+Space) for service names, parameters, and pipeline fields
- I/O signature editing via the standard I/O tab
- Save compiles on the server and reports errors

## Creating Services

### From Designer

1. Open the package where you want the service
2. Use `wmscript.admin:createService` with:
   - `servicePath`: e.g. `myPackage.folder:myService`
   - `packageName`: e.g. `myPackage`
   - `source`: (optional) initial WmScript source code
3. The service appears in the Navigator — double-click to edit
4. Define inputs/outputs in the I/O tab
5. Write code in the Source tab
6. Save (Ctrl+S) to compile

### From the Admin API

```
# Create a service
POST /invoke/wmscript.admin:createService
{
  "servicePath": "myPackage.utils:formatDate",
  "packageName": "myPackage",
  "source": "formatted = invoke pub.date:formatDate(pattern: \"yyyy-MM-dd\", date: inputDate)"
}

# Edit source
POST /invoke/wmscript.admin:saveSource
{
  "servicePath": "myPackage.utils:formatDate",
  "source": "..."
}
```

### From the CLI

The compiler JAR can compile `.wms` files directly:

```bash
# Compile a single file to Java source
java -jar wmscript-compiler.jar compile service.wms -o output/

# Compile to .class (needs IS JARs on classpath)
java -jar wmscript-compiler.jar compile service.wms -o output/ -cp /path/to/is/lib/wm-isserver.jar

# Compile all services in an IS package
java -jar wmscript-compiler.jar compile-package /path/to/packages/MyPackage --is-home /path/to/wm
```

## Language Reference

### Variables and Assignment

Variables are pipeline fields. Assignment reads from and writes to the IS pipeline (IData).

```
name = "Alice"                     // set pipeline variable
order.customer.name = "Bob"        // nested document field
items[] = "apple"                  // append to array
items[] = "banana"
```

#### Destructuring

Extract multiple fields from a document in one statement:

```
{firstName, lastName} = invoke myPkg.users:getUser(id: userId)
```

### Service Invocation

Call any IS service with named arguments:

```
// Simple invoke — result merges into pipeline
invoke pub.flow:debugLog(message: "Starting process")

// Capture a single output field
result = invoke pub.math:addInts(num1: 5, num2: 3)

// Destructure multiple outputs
{value, error} = invoke myPkg.svc:doSomething(input: data)
```

### Control Flow

#### If / Elif / Else

```
if status == "active" and priority > 5:
    log.info(f"Processing high-priority item: {itemId}")
    invoke myPkg.process:handleUrgent(id: itemId)
elif status == "pending":
    log.info("Item pending, skipping")
else:
    log.warn(f"Unknown status: {status}")
end
```

#### For Loops

```
// Iterate over an array
for item in items:
    log.info(f"Processing: {item}")
end

// Iterate with key-value pairs over a document
for key, value in entries(config):
    log.debug(f"{key} = {value}")
end
```

#### While Loops

```
retries = 0
while retries < 3:
    try:
        invoke myPkg.api:callExternal(payload: data)
        break
    catch err:
        retries = retries + 1
        log.warn(f"Retry {retries}: {err}")
    end
end
```

#### Loop Control

- `break` — exit the loop
- `continue` (or `skip`) — skip to the next iteration

### Error Handling

```
try:
    result = invoke myPkg.risky:operation(input: data)
catch error:
    log.error(f"Operation failed: {error}")
    raise f"Unable to process: {error}"
end
```

### Expressions

#### Operators

| Operator | Description |
|----------|-------------|
| `+`, `-`, `*`, `/`, `%` | Arithmetic (`+` also concatenates strings) |
| `==`, `!=`, `<`, `>`, `<=`, `>=` | Comparison |
| `and`, `or`, `!` (or `&&`, `\|\|`) | Logical |
| `?.` | Null-safe field access |
| `??` | Null coalesce (default value) |
| `? :` | Ternary |

#### Null-Safe Navigation

```
city = order?.customer?.address?.city ?? "Unknown"
```

#### Array Projection and Filtering

```
// Extract a field from every element
orderIds = orders[].id

// Filter by field value
activeOrders = orders[status == "active"]

// Combine projection and filtering
activeNames = orders[status == "active"][].customerName
```

#### Document and Array Literals

```
address = {street: "123 Main St", city: "Springfield", zip: "62701"}
tags = ["urgent", "reviewed", "approved"]
```

#### Template Strings

Embed expressions in strings with `f"..."`:

```
message = f"Order {orderId} for {customer.name}: total ${amount}"
```

### Logging

```
log.error("Something went wrong")
log.warn(f"Retrying operation, attempt {retries}")
log.info("Processing complete")
log.debug(f"Pipeline state: {pipeline}")
```

Logs are written via IS's `pub.flow:debugLog` and appear in the server log.

### Return

```
if input == null:
    return    // early return
end

// return with a value (sets $return in pipeline)
return f"Processed {count} items"
```

### Built-in Functions

#### Type Conversion

| Function | Description | Example |
|----------|-------------|---------|
| `str(val)` | Convert to string | `str(42)` -> `"42"` |
| `num(val)` | Convert to number | `num("3.14")` -> `3.14` |
| `int(val)` | Convert to integer | `int("42")` -> `42` |
| `date(val, fmt)` | Parse date | `date("2024-01-15", "yyyy-MM-dd")` |

#### String Functions

| Function | Description | Example |
|----------|-------------|---------|
| `len(s)` | String/array length | `len("hello")` -> `5` |
| `upper(s)` | Uppercase | `upper("hello")` -> `"HELLO"` |
| `lower(s)` | Lowercase | `lower("HELLO")` -> `"hello"` |
| `trim(s)` | Trim whitespace | `trim("  hi  ")` -> `"hi"` |
| `split(s, delim)` | Split to array | `split("a,b,c", ",")` -> `["a","b","c"]` |
| `join(arr, delim)` | Join array | `join(tags, ", ")` -> `"a, b, c"` |
| `replace(s, old, new)` | Replace substring | `replace("hello", "l", "r")` -> `"herro"` |
| `contains(s, sub)` | Check contains | `contains("hello", "ell")` -> `true` |
| `startsWith(s, prefix)` | Check prefix | `startsWith("hello", "he")` -> `true` |
| `endsWith(s, suffix)` | Check suffix | `endsWith("hello", "lo")` -> `true` |
| `substring(s, start[, end])` | Extract substring | `substring("hello", 1, 4)` -> `"ell"` |
| `indexOf(s, sub)` | Find index | `indexOf("hello", "ll")` -> `2` |

#### Collection Functions

| Function | Description | Example |
|----------|-------------|---------|
| `len(arr)` | Array length | `len(items)` -> `3` |
| `sum(arr)` | Sum numeric array | `sum(amounts)` -> `150.0` |
| `min(arr)` | Minimum value | `min(scores)` -> `42` |
| `max(arr)` | Maximum value | `max(scores)` -> `99` |
| `sort(arr)` | Sort array | `sort(names)` -> `["a","b","c"]` |
| `contains(arr, val)` | Check membership | `contains(tags, "urgent")` -> `true` |
| `keys(doc)` | Document keys | `keys(config)` -> `["host","port"]` |
| `values(doc)` | Document values | `values(config)` -> `["localhost","8080"]` |
| `entries(doc)` | Key-value pairs | For use with `for k, v in entries(doc)` |
| `map(arr, field)` | Extract field | `map(orders, "total")` -> `[100, 200]` |
| `filter(arr, field, val)` | Filter by field | `filter(orders, "status", "active")` |
| `reduce(arr, field, init)` | Sum a field | `reduce(orders, "amount", 0)` -> `300` |

#### Math Functions

| Function | Description | Example |
|----------|-------------|---------|
| `abs(n)` | Absolute value | `abs(-5)` -> `5` |
| `round(n)` | Round | `round(3.7)` -> `4` |
| `floor(n)` | Floor | `floor(3.9)` -> `3` |
| `ceil(n)` | Ceiling | `ceil(3.1)` -> `4` |

#### Introspection

| Function | Description | Returns |
|----------|-------------|---------|
| `typeof(val)` | Type name | `"string"`, `"number"`, `"document"`, `"documentList"`, `"list"`, `"null"` |
| `exists(key)` | Pipeline key exists | `true` / `false` |

### Comments

```
// Line comment
# Also a line comment
/* Block
   comment */
```

### Pipeline Access

The special variable `pipeline` gives direct access to the full IData:

```
// Pass entire pipeline to another service
invoke myPkg.utils:logPipeline(data: pipeline)
```

## Examples

### Process an Order

```
// @input orderId : string
// @input items : documentList
// @output total : string
// @output status : string

log.info(f"Processing order {orderId} with {len(items)} items")

// Calculate total from item amounts
total = str(reduce(items, "price", 0))

// Check inventory for each item
for item in items:
    {available} = invoke inventory.stock:check(sku: item.sku, quantity: item.quantity)
    if !available:
        status = "backorder"
        log.warn(f"Item {item.sku} not available in requested quantity")
        return
    end
end

// All items available — place the order
invoke orders.management:create(orderId: orderId, items: items, total: total)
status = "confirmed"
```

### REST API Integration with Retry

```
// @input endpoint : string
// @input payload : document
// @output response : document
// @output success : string

retries = 0
maxRetries = 3
success = "false"

while retries < num(maxRetries):
    try:
        {response} = invoke pub.client:http(
            url: endpoint,
            method: "POST",
            data: payload
        )
        success = "true"
        break
    catch err:
        retries = retries + 1
        log.warn(f"API call failed (attempt {retries}/{maxRetries}): {err}")
        if retries < num(maxRetries):
            invoke pub.flow:sleep(seconds: str(retries * 2))
        end
    end
end

if success == "false":
    raise f"API call to {endpoint} failed after {maxRetries} attempts"
end
```

### Transform and Route Documents

```
// @input documents : documentList
// @output processed : string
// @output errors : stringList

count = 0
for doc in documents:
    try:
        docType = doc.type ?? "unknown"

        if docType == "invoice":
            invoke accounting.process:invoice(data: doc)
        elif docType == "purchase_order":
            invoke procurement.process:po(data: doc)
        elif docType == "shipment":
            invoke logistics.process:shipment(data: doc)
        else:
            log.warn(f"Unknown document type: {docType}, id: {doc.id}")
            errors[] = f"Unknown type '{docType}' for document {doc.id}"
            continue
        end

        count = count + 1
    catch err:
        errors[] = f"Error processing {doc.id}: {err}"
        log.error(f"Failed to process document {doc.id}: {err}")
    end
end

processed = str(count)
```

### Working with Documents

```
// Build a response document
response = {
    status: "success",
    timestamp: invoke pub.date:currentDate(),
    data: {
        customer: customerName,
        orderCount: str(len(orders)),
        totalRevenue: str(reduce(orders, "amount", 0))
    },
    tags: ["processed", "verified"]
}

// Navigate nested structures safely
city = response?.data?.customer?.address?.city ?? "N/A"
```

## Building from Source

### Compiler

```bash
cd wmscript-compiler

# Build the runtime JAR (includes compiler + ANTLR + runtime)
gradle runtimeJar
# Output: build/libs/wmscript-runtime-0.1.0.jar

# Build the CLI compiler JAR
gradle compilerJar
# Output: build/libs/wmscript-compiler-0.1.0.jar

# Run tests
gradle test
```

The build requires IS JARs on the compile classpath. By default, it looks for them at `../../wm/IntegrationServer/lib/` and `../../wm/common/lib/` relative to the compiler directory. Adjust the paths in `build.gradle` if your IS is installed elsewhere.

### Designer Plugin

```bash
cd wmscript-designer-plugin

# Set the classpath to Designer's plugins
ECLIPSE_CP=$(echo /path/to/Designer/eclipse/plugins/*.jar | tr ' ' ':')

# Compile
javac --release 11 -cp "$ECLIPSE_CP" -d bin \
  src/com/softwareag/is/wmscript/editor/*.java

# Package
jar cf com.softwareag.is.wmscript.editor_0.1.0.jar \
  -C bin com -C . plugin.xml -C . META-INF -C . grammars -C . icons
```

## Project Structure

```
wmscript/
├── wmscript-compiler/                    # Compiler and runtime
│   ├── build.gradle                      # Gradle build (ANTLR4, Java 11)
│   └── src/
│       ├── main/
│       │   ├── antlr/.../WmScript.g4     # ANTLR4 grammar
│       │   └── java/.../
│       │       ├── compiler/
│       │       │   ├── WmScriptCompiler.java      # Parser, CLI, package compiler
│       │       │   └── JavaCodeGenerator.java      # AST visitor -> Java source
│       │       └── runtime/
│       │           ├── PipelineContext.java         # Runtime helper (wraps IData)
│       │           ├── WmScriptService.java         # IS BaseService subclass
│       │           ├── WmScriptServiceFactory.java  # IS plugin node factory
│       │           └── WmScriptAdmin.java           # Admin services (get/save/create)
│       └── test/
│           └── java/.../CompilerTest.java
│
├── wmscript-designer-plugin/             # Designer IDE plugin
│   ├── META-INF/MANIFEST.MF             # OSGi bundle metadata
│   ├── plugin.xml                        # Eclipse extension points
│   ├── grammars/
│   │   ├── wmscript.tmLanguage.json      # TextMate syntax grammar
│   │   └── language-configuration.json   # Bracket matching, folding
│   └── src/.../editor/
│       ├── WmScriptEditor.java           # Multi-page editor (Source + I/O tabs)
│       ├── WmScriptContentAssistProcessor.java  # Autocompletion
│       ├── WmScriptServiceProposal.java  # Service invocation templates
│       ├── WmScriptAssetCreator.java     # Navigator integration
│       └── ISWmScriptServiceNode.java    # Navigator node model
│
└── wmscript-package/                     # IS package (deploy to IS)
    ├── manifest.v3                       # Package metadata
    ├── config/nsplugins.cnf              # Registers wmscript service type
    └── ns/
        └── wmscript/
            ├── admin/                    # Admin services (getSource, saveSource, createService)
            └── samples/
                └── helloWorld/
                    ├── node.ndf          # IS service definition
                    └── service.wms       # Sample WmScript source
```

## How It Works

1. **Service Registration**: The `nsplugins.cnf` file tells IS to load `WmScriptServiceFactory`, which registers the `wmscript` service type. When IS encounters a `node.ndf` with `svc_type=wmscript`, it creates a `WmScriptService` instance.

2. **Compilation**: When you save a `.wms` file (via Designer or the admin API), the `WmScriptAdmin.saveSource` service:
   - Parses the source using the ANTLR4 grammar
   - Generates Java source via `JavaCodeGenerator`
   - Compiles to bytecode with `javac --release 11`
   - Places the `.class` file in `code/classes/wmscript/generated/`
   - Reloads the package so IS picks up the new class

3. **Execution**: When a WmScript service is invoked, `WmScriptService.baseInvoke()` loads the compiled class via the package classloader and calls its static `main(IData pipeline)` method. The generated code uses `PipelineContext` to interact with the pipeline.

4. **Class Name Encoding**: IS namespace names (e.g., `myPkg.folder:svcName`) are encoded as Java class names using a deterministic scheme: `.` -> `_0`, `:` -> `_1`, `_` -> `_2`. This ensures collision-free, reversible mapping.

## License

MIT
