package de.metis.mail.threading;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M3-003: Message threading (JWZ algorithm, simplified).
 *
 * <p>Builds conversation threads from Message-ID / References / In-Reply-To
 * headers and maps each message's database id to a thread id
 * (the smallest db id within the thread).
 *
 * <p>Robust against: missing headers, duplicate message-ids, self-references,
 * reference cycles and empty (never-seen) parent containers.
 */
public final class MessageThreader {

    /** One message for threading purposes. */
    public record ThreadInput(long dbId, String messageId, String inReplyTo, String references) {
    }

    private static final Pattern MSG_ID_TOKEN = Pattern.compile("<[^>\\s]+>|[^\\s<>]+");

    private static final class Container {
        ThreadInput msg;                 // null for "empty" (referenced but unseen) containers
        final List<Long> duplicateDbIds = new ArrayList<>(); // further rows with the same message-id
        Container parent;
        final List<Container> children = new ArrayList<>();
    }

    private final Map<String, Container> idTable = new HashMap<>();

    /**
     * Compute dbId -> threadId for all given messages.
     */
    public Map<Long, Long> computeThreads(List<ThreadInput> inputs) {
        idTable.clear();
        List<ThreadInput> withoutId = new ArrayList<>();

        for (ThreadInput in : inputs) {
            String mid = normalize(in.messageId());
            if (mid == null) {
                withoutId.add(in);
                continue;
            }
            Container self = idTable.computeIfAbsent(mid, k -> new Container());
            if (self.msg == null) {
                self.msg = in; // first occurrence wins on duplicate ids
            } else {
                self.duplicateDbIds.add(in.dbId()); // same id, additional DB row
            }

            Container parent = null;
            for (String ref : parseRefs(in)) {
                if (ref.equals(mid)) continue; // self-reference guard
                Container pc = idTable.computeIfAbsent(ref, k -> new Container());
                if (parent != null && pc != parent && pc.parent == null && !isAncestor(pc, parent)) {
                    pc.parent = parent;
                    parent.children.add(pc);
                }
                parent = pc;
            }
            if (parent != null && parent != self && self.parent == null && !isAncestor(self, parent)) {
                parent.children.add(self);
                self.parent = parent;
            }
        }

        // Collect roots
        List<Container> roots = new ArrayList<>();
        for (Container c : idTable.values()) {
            if (c.parent == null) roots.add(c);
        }

        // Promote children of empty roots (JWZ pruning)
        List<Container> finalRoots = new ArrayList<>();
        for (Container r : roots) {
            if (r.msg == null) {
                for (Container ch : r.children) {
                    ch.parent = null;
                    finalRoots.add(ch);
                }
            } else {
                finalRoots.add(r);
            }
        }

        Map<Long, Long> result = new HashMap<>();
        for (Container r : finalRoots) {
            long threadId = minDbId(r);
            if (threadId >= 0) {
                assignThread(r, threadId, result);
            }
        }
        // Messages without Message-ID: single-message threads
        for (ThreadInput in : withoutId) {
            result.putIfAbsent(in.dbId(), in.dbId());
        }
        // Safety net: anything unassigned becomes its own thread
        for (ThreadInput in : inputs) {
            result.putIfAbsent(in.dbId(), in.dbId());
        }
        return result;
    }

    /** True if {@code maybeAncestor} is an ancestor of {@code node} (cycle guard). */
    private static boolean isAncestor(Container maybeAncestor, Container node) {
        Container c = node;
        while (c != null) {
            if (c == maybeAncestor) return true;
            c = c.parent;
        }
        return false;
    }

    private static long minDbId(Container root) {
        long min = Long.MAX_VALUE;
        List<Container> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            Container c = stack.remove(stack.size() - 1);
            if (c.msg != null) {
                min = Math.min(min, c.msg.dbId());
                for (long dup : c.duplicateDbIds) min = Math.min(min, dup);
            }
            stack.addAll(c.children);
        }
        return min == Long.MAX_VALUE ? -1 : min;
    }

    private static void assignThread(Container node, long threadId, Map<Long, Long> out) {
        List<Container> stack = new ArrayList<>();
        stack.add(node);
        while (!stack.isEmpty()) {
            Container c = stack.remove(stack.size() - 1);
            if (c.msg != null) {
                out.put(c.msg.dbId(), threadId);
                for (long dup : c.duplicateDbIds) out.put(dup, threadId);
            }
            stack.addAll(c.children);
        }
    }

    private static List<String> parseRefs(ThreadInput in) {
        List<String> refs = new ArrayList<>();
        if (in.references() != null && !in.references().isBlank()) {
            Matcher m = MSG_ID_TOKEN.matcher(in.references());
            while (m.find()) refs.add(m.group());
        } else if (in.inReplyTo() != null && !in.inReplyTo().isBlank()) {
            Matcher m = MSG_ID_TOKEN.matcher(in.inReplyTo());
            if (m.find()) refs.add(m.group());
        }
        return refs;
    }

    private static String normalize(String messageId) {
        if (messageId == null || messageId.isBlank()) return null;
        String trimmed = messageId.trim();
        Matcher m = MSG_ID_TOKEN.matcher(trimmed);
        return m.find() ? m.group() : null;
    }
}
