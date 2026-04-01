grammar WmScript;

// ============================================================
// Parser Rules
// ============================================================

program
    : NEWLINE* (statement (NEWLINE+ statement)*)? NEWLINE* EOF
    ;

statement
    : assignment
    | invokeStatement
    | ifStatement
    | forStatement
    | whileStatement
    | tryStatement
    | raiseStatement
    | returnStatement
    | appendStatement
    | logStatement
    | skipStatement
    | breakStatement
    | continueStatement
    ;

// --- Assignment ---
assignment
    : simpleAssignment
    | destructureAssignment
    ;

simpleAssignment
    : target '=' expression
    ;

destructureAssignment
    : '{' IDENTIFIER (',' IDENTIFIER)* '}' '=' expression
    ;

target
    : IDENTIFIER ('.' IDENTIFIER)*
    ;

// --- Append to array ---
appendStatement
    : target '[' ']' '=' expression
    ;

// --- Invoke ---
invokeStatement
    : 'invoke' serviceRef '(' argumentList? ')'
    ;

invokeExpression
    : 'invoke' serviceRef '(' argumentList? ')'
    ;

serviceRef
    : SERVICE_REF
    ;

argumentList
    : argument (',' argument)*
    ;

argument
    : argumentName ':' expression
    ;

argumentName
    : IDENTIFIER
    ;

// --- If / Elif / Else ---
ifStatement
    : 'if' expression ':' block
      elifClause*
      elseClause?
      'end'
    ;

elifClause
    : 'elif' expression ':' block
    ;

elseClause
    : 'else' ':' block
    ;

// --- For loop (single var or key,value destructuring) ---
forStatement
    : 'for' IDENTIFIER 'in' expression ':' block 'end'
    | 'for' IDENTIFIER ',' IDENTIFIER 'in' expression ':' block 'end'
    ;

// --- While loop ---
whileStatement
    : 'while' expression ':' block 'end'
    ;

// --- Try / Catch ---
tryStatement
    : 'try' ':' block catchClause 'end'
    ;

catchClause
    : 'catch' IDENTIFIER ':' block
    ;

// --- Raise ---
raiseStatement
    : 'raise' expression
    ;

// --- Return ---
returnStatement
    : 'return' expression?
    ;

// --- Log ---
logStatement
    : 'log' '.' logLevel '(' expression ')'
    ;

logLevel
    : IDENTIFIER  // matches: error, warn, info, debug (validated at codegen)
    ;

// --- Skip / Break / Continue ---
skipStatement   : 'skip' ;
breakStatement  : 'break' ;
continueStatement : 'continue' ;

// --- Block (newline-separated statements) ---
block
    : NEWLINE+ (statement (NEWLINE+ statement)*)? NEWLINE+
    ;

// ============================================================
// Expressions
// ============================================================

expression
    : invokeExpression                                          # invokeExpr
    | expression '?.' IDENTIFIER                                # nullSafeAccess
    | expression '.' IDENTIFIER                                 # dotAccess
    | expression '[' ']' '.' IDENTIFIER                         # arrayProjection
    | expression '[' IDENTIFIER '==' expression ']'              # filterAccess
    | expression '[' expression ']'                              # indexAccess
    | '!' expression                                             # notExpr
    | expression ('*' | '/' | '%') expression                    # mulDivExpr
    | expression ('+' | '-') expression                          # addSubExpr
    | expression ('==' | '!=' | '<' | '>' | '<=' | '>=') expression # comparisonExpr
    | expression ('&&' | 'and') expression                       # andExpr
    | expression ('||' | 'or') expression                        # orExpr
    | expression '??' expression                                 # nullCoalesceExpr
    | expression '?' expression ':' expression                   # ternaryExpr
    | '{' documentEntry (',' documentEntry)* '}'                 # documentLiteral
    | '[' expression (',' expression)* ']'                       # arrayLiteral
    | builtinCall                                                # builtinExpr
    | STRING_LITERAL                                             # stringLiteral
    | TEMPLATE_STRING                                            # templateString
    | NUMBER                                                     # numberLiteral
    | 'true'                                                     # trueLiteral
    | 'false'                                                    # falseLiteral
    | 'null'                                                     # nullLiteral
    | 'pipeline'                                                 # pipelineLiteral
    | target                                                     # variableExpr
    | '(' expression ')'                                         # parenExpr
    ;

documentEntry
    : IDENTIFIER ':' expression
    | STRING_LITERAL ':' expression
    ;

builtinCall
    : builtinName '(' (expression (',' expression)*)? ')'
    ;

builtinName
    : 'num'     // string -> number
    | 'str'     // any -> string
    | 'int'     // string -> integer
    | 'date'    // string + format -> date
    | 'len'     // array/string length
    | 'sum'     // sum of numeric array
    | 'min'     // min of array
    | 'max'     // max of array
    | 'join'    // join array to string
    | 'split'   // split string to array
    | 'keys'    // keys of a document
    | 'values'  // values of a document
    | 'entries' // key-value pairs of a document
    | 'exists'  // check if pipeline variable exists
    | 'typeof'  // type of a value
    | 'contains'// check if string/array contains value
    | 'trim'    // trim whitespace from string
    | 'replace' // replace in string
    | 'sort'    // sort an array
    | 'map'     // transform array elements
    | 'filter'  // filter array elements
    | 'reduce'  // reduce array to single value
    | 'upper'   // uppercase string
    | 'lower'   // lowercase string
    | 'startsWith' // check string prefix
    | 'endsWith'   // check string suffix
    | 'substring'  // extract substring
    | 'indexOf'    // find index of substring
    | 'abs'        // absolute value
    | 'round'      // round number
    | 'floor'      // floor number
    | 'ceil'       // ceiling number
    ;

// ============================================================
// Lexer Rules
// ============================================================

// Keywords (must come before IDENTIFIER)
INVOKE  : 'invoke' ;
IF      : 'if' ;
ELIF    : 'elif' ;
ELSE    : 'else' ;
FOR     : 'for' ;
IN      : 'in' ;
WHILE   : 'while' ;
TRY     : 'try' ;
CATCH   : 'catch' ;
RAISE   : 'raise' ;
RETURN  : 'return' ;
END     : 'end' ;
SKIP_   : 'skip' ;
BREAK   : 'break' ;
CONTINUE: 'continue' ;
LOG     : 'log' ;
AND     : 'and' ;
OR      : 'or' ;
TRUE    : 'true' ;
FALSE   : 'false' ;
NULL    : 'null' ;

// Service reference: dotted.path:serviceName
SERVICE_REF
    : [a-zA-Z_] [a-zA-Z0-9_]* ('.' [a-zA-Z_] [a-zA-Z0-9_]*)* ':' [a-zA-Z_] [a-zA-Z0-9_]*
    ;

// Identifiers
IDENTIFIER
    : [a-zA-Z_] [a-zA-Z0-9_]*
    ;

// Literals
NUMBER
    : '-'? [0-9]+ ('.' [0-9]+)?
    ;

STRING_LITERAL
    : '"' (~["\\\n\r] | '\\' .)* '"'
    | '\'' (~['\\\n\r] | '\\' .)* '\''
    ;

// Template strings with interpolation: "Hello {name}, you have {count} items"
TEMPLATE_STRING
    : 'f"' (~["\\\n\r] | '\\' . | '{' ~[}]* '}')* '"'
    | 'f\'' (~['\\\n\r] | '\\' . | '{' ~[}]* '}')* '\''
    ;

// Whitespace and comments
NEWLINE     : ('\r'? '\n' | '\r')+ ;
WS          : [ \t]+ -> skip ;
LINE_COMMENT: '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT: '/*' .*? '*/' -> skip ;
HASH_COMMENT: '#' ~[\r\n]* -> skip ;
