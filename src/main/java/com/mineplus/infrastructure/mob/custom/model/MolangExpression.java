package com.mineplus.infrastructure.mob.custom.model;

/**
 * A compiled, sandboxed Molang expression: the slice of the language Blockbench
 * actually writes into animation keys.
 *
 * <p>Supported: numeric literals, {@code + - * / %}, parentheses, unary minus,
 * {@code math.sin|cos|abs|sqrt|floor|ceil|round|pow|min|max|mod|clamp|lerp},
 * the constants {@code math.pi} and {@code math.e}, and the queries
 * {@code q.anim_time} and {@code q.life_time}. Any other identifier (unknown
 * queries, {@code variable.*}, temp variables) parses but evaluates to {@code 0}
 * and raises the {@link #usesUnknownVariables} flag, so callers that need a
 * pure constant (rest poses, pack-time folding) can reject the expression.</p>
 *
 * <p>Evaluation allocates nothing and touches nothing outside its two inputs,
 * so it is safe on the pose worker thread and cannot read world state.</p>
 */
public final class MolangExpression {

    /** Single-node evaluation contract; instances are immutable closures. */
    private interface Node {
        double eval(double animTime, double lifeTime);
    }

    private static final Node ZERO = (animTime, lifeTime) -> 0.0;

    private final Node root;
    private final boolean usesUnknownVariables;
    private final boolean hasVariables;

    private MolangExpression(Node root, boolean usesUnknownVariables, boolean hasVariables) {
        this.root = root;
        this.usesUnknownVariables = usesUnknownVariables;
        this.hasVariables = hasVariables;
    }

    /** {@code true} when the expression names a query this evaluator does not model. */
    public boolean usesUnknownVariables() {
        return usesUnknownVariables;
    }

    /** {@code true} when the expression reads any query ({@code q.*}); such expressions cannot fold to a constant. */
    public boolean hasVariables() {
        return hasVariables;
    }

    /** Evaluates the expression for the given clip time (seconds) and instance age. */
    public float eval(float animTime, float lifeTime) {
        double value = root.eval(animTime, lifeTime);
        return (float) (Double.isFinite(value) ? value : 0.0);
    }

    /**
     * Parses {@code expr}.
     *
     * @return the compiled expression, or {@code null} when it is not valid Molang
     */
    public static MolangExpression compile(String expr) {
        if (expr == null) {
            return null;
        }
        String trimmed = expr.trim();
        // A quoted Molang string ('25') is just its content.
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '\'' && trimmed.charAt(trimmed.length() - 1) == '\'') {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            Parser parser = new Parser(trimmed);
            Node root = parser.parseExpression();
            if (!parser.atEnd()) {
                return null;
            }
            return new MolangExpression(root, parser.unknownVariables, parser.queries > 0);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Recursive-descent parser
    // ------------------------------------------------------------------

    private static final class Parser {
        private final String source;
        private int pos;
        private boolean unknownVariables;
        private int queries;

        Parser(String source) {
            this.source = source;
        }

        boolean atEnd() {
            skipWhitespace();
            return pos >= source.length();
        }

        Node parseExpression() {
            Node left = parseTerm();
            while (true) {
                skipWhitespace();
                if (match('+')) {
                    Node right = parseTerm();
                    left = add(left, right);
                } else if (match('-')) {
                    Node right = parseTerm();
                    left = subtract(left, right);
                } else {
                    return left;
                }
            }
        }

        private Node parseTerm() {
            Node left = parseUnary();
            while (true) {
                skipWhitespace();
                if (match('*')) {
                    Node right = parseUnary();
                    left = multiply(left, right);
                } else if (match('/')) {
                    Node right = parseUnary();
                    left = divide(left, right);
                } else if (match('%')) {
                    Node right = parseUnary();
                    left = modulo(left, right);
                } else {
                    return left;
                }
            }
        }

        private Node parseUnary() {
            skipWhitespace();
            if (match('-')) {
                Node inner = parseUnary();
                return (animTime, lifeTime) -> -inner.eval(animTime, lifeTime);
            }
            if (match('+')) {
                return parseUnary();
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            skipWhitespace();
            if (match('(')) {
                Node inner = parseExpression();
                if (!match(')')) {
                    throw new IllegalArgumentException("unbalanced parenthesis");
                }
                return inner;
            }
            int start = pos;
            while (pos < source.length() && isIdentifierPart(source.charAt(pos))) {
                pos++;
            }
            if (pos == start) {
                throw new IllegalArgumentException("expected a value at " + rest());
            }
            String name = source.substring(start, pos).toLowerCase(java.util.Locale.ROOT);
            skipWhitespace();
            if (match('(')) {
                return parseFunction(name);
            }
            char firstChar = name.charAt(0);
            if (Character.isDigit(firstChar) || firstChar == '.') {
                try {
                    double literal = Double.parseDouble(name);
                    return (animTime, lifeTime) -> literal;
                } catch (NumberFormatException notANumber) {
                    throw new IllegalArgumentException("not a number: " + name);
                }
            }
            return parseValue(name);
        }

        private Node parseFunction(String name) {
            Node first = parseExpression();
            Node second = null;
            Node third = null;
            if (match(',')) {
                second = parseExpression();
            }
            if (second != null && match(',')) {
                third = parseExpression();
            }
            if (!match(')')) {
                throw new IllegalArgumentException("unbalanced parenthesis in " + name);
            }
            return switch (name) {
                case "math.sin" -> unary(first, Math::sin);
                case "math.cos" -> unary(first, Math::cos);
                case "math.abs" -> unary(first, Math::abs);
                case "math.sqrt" -> unary(first, v -> Math.sqrt(Math.max(0.0, v)));
                case "math.floor" -> unary(first, Math::floor);
                case "math.ceil" -> unary(first, Math::ceil);
                case "math.round" -> unary(first, v -> (double) Math.round(v));
                case "math.pow" -> binary(first, second, Math::pow);
                case "math.mod" -> binary(first, second, (a, b) -> b == 0.0 ? 0.0 : a % b);
                case "math.min" -> binary(first, second, Math::min);
                case "math.max" -> binary(first, second, Math::max);
                case "math.clamp" -> ternary(first, second, third,
                        (v, low, high) -> Math.max(low, Math.min(high, v)));
                case "math.lerp" -> ternary(first, second, third,
                        (a, b, alpha) -> a + (b - a) * alpha);
                default -> unknown(name, first, second, third);
            };
        }

        /** An unknown function folds all arguments (kept for side-effect-free arity tolerance) and yields 0. */
        private Node unknown(String name, Node first, Node second, Node third) {
            unknownVariables = true;
            Node args = first;
            if (second != null) {
                args = add(args, second);
            }
            if (third != null) {
                args = add(args, third);
            }
            Node ignored = args;
            return (animTime, lifeTime) -> {
                ignored.eval(animTime, lifeTime);
                return 0.0;
            };
        }

        private Node parseValue(String name) {
            switch (name) {
                case "q.anim_time", "query.anim_time" -> {
                    queries++;
                    return (animTime, lifeTime) -> animTime;
                }
                case "q.life_time", "query.life_time" -> {
                    queries++;
                    return (animTime, lifeTime) -> lifeTime;
                }
                case "math.pi" -> {
                    return (animTime, lifeTime) -> Math.PI;
                }
                case "math.e" -> {
                    return (animTime, lifeTime) -> Math.E;
                }
                case "this" -> {
                    return ZERO;
                }
                default -> {
                    // Unknown query or variable.*: parses fine, samples as 0.
                    unknownVariables = true;
                    return ZERO;
                }
            }
        }

        private void skipWhitespace() {
            while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
                pos++;
            }
        }

        private boolean match(char c) {
            skipWhitespace();
            if (pos < source.length() && source.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private static boolean isIdentifierPart(char c) {
            return Character.isLetterOrDigit(c) || c == '.' || c == '_';
        }

        private String rest() {
            return source.substring(Math.min(pos, source.length()));
        }

        private interface DoubleTriple {
            double apply(double a, double b, double c);
        }

        private static Node unary(Node first, java.util.function.DoubleUnaryOperator op) {
            return (animTime, lifeTime) -> op.applyAsDouble(first.eval(animTime, lifeTime));
        }

        private static Node binary(Node first, Node second, java.util.function.DoubleBinaryOperator op) {
            if (second == null) {
                throw new IllegalArgumentException("missing argument");
            }
            return (animTime, lifeTime) -> op.applyAsDouble(
                    first.eval(animTime, lifeTime), second.eval(animTime, lifeTime));
        }

        private static Node ternary(Node first, Node second, Node third, DoubleTriple op) {
            if (second == null || third == null) {
                throw new IllegalArgumentException("missing argument");
            }
            return (animTime, lifeTime) -> op.apply(
                    first.eval(animTime, lifeTime),
                    second.eval(animTime, lifeTime),
                    third.eval(animTime, lifeTime));
        }

        private static Node add(Node a, Node b) {
            return (animTime, lifeTime) -> a.eval(animTime, lifeTime) + b.eval(animTime, lifeTime);
        }

        private static Node subtract(Node a, Node b) {
            return (animTime, lifeTime) -> a.eval(animTime, lifeTime) - b.eval(animTime, lifeTime);
        }

        private static Node multiply(Node a, Node b) {
            return (animTime, lifeTime) -> a.eval(animTime, lifeTime) * b.eval(animTime, lifeTime);
        }

        private static Node divide(Node a, Node b) {
            return (animTime, lifeTime) -> {
                double divisor = b.eval(animTime, lifeTime);
                return divisor == 0.0 ? 0.0 : a.eval(animTime, lifeTime) / divisor;
            };
        }

        private static Node modulo(Node a, Node b) {
            return (animTime, lifeTime) -> {
                double divisor = b.eval(animTime, lifeTime);
                return divisor == 0.0 ? 0.0 : a.eval(animTime, lifeTime) % divisor;
            };
        }
    }
}
