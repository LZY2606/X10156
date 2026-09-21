package webhooklab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Assembles read models for the browser UI. */
public final class StateView {

    private StateView() {}

    public static Map<String, Object> full(Store store) {
        Map<String, Object> root = Json.obj();
        root.put("nowMs", store.nowMs);

        List<Object> endpoints = new ArrayList<>();
        for (Model.Endpoint ep : store.endpoints.values()) {
            Map<String, Object> m = ep.toJson();
            int parked = 0;
            for (String id : store.pendingAttemptIds) {
                Model.Attempt a = store.attempts.get(id);
                if (a != null && a.endpointId().equals(ep.id())) parked++;
            }
            m.put("pendingAttempts", parked);
            endpoints.add(m);
        }
        root.put("endpoints", endpoints);
        root.put("behaviors", Receiver.behaviorCatalog());

        List<Object> chains = new ArrayList<>();
        for (Model.Chain c : store.chains.values()) {
            chains.add(chainJson(store, c));
        }
        root.put("chains", chains);

        List<Object> dead = new ArrayList<>();
        for (Model.Chain c : store.chains.values()) {
            if (c.status() == Model.ChainStatus.DEAD || c.status() == Model.ChainStatus.FAILED) {
                dead.add(chainJson(store, c));
            }
        }
        root.put("deadLetters", dead);

        List<Object> rlog = new ArrayList<>();
        for (Model.ReceiverEntry e : store.receiverLog) rlog.add(e.toJson());
        root.put("receiverLog", rlog);

        return root;
    }

    public static Map<String, Object> chainJson(Store store, Model.Chain c) {
        Map<String, Object> m = c.toJson();
        Model.Event ev = store.events.get(c.eventId());
        m.put("event", ev != null ? ev.toJson() : null);
        List<Object> attempts = new ArrayList<>();
        List<Model.Attempt> list = store.attemptsOf(c.id());
        list.sort(Comparator.comparingInt(Model.Attempt::attemptNo));
        for (Model.Attempt a : list) {
            Map<String, Object> am = a.toJson();
            Model.AttemptResult r = store.results.get(a.id());
            am.put("result", r != null ? r.toJson() : null);
            am.put("pending", r == null);
            attempts.add(am);
        }
        m.put("attempts", attempts);
        Model.Chain origin = c.originChainId() != null ? store.chains.get(c.originChainId()) : null;
        m.put("originChain", origin != null && origin != c ? origin.toJson() : null);
        return m;
    }

    public static Map<String, Object> chain(Store store, String chainId) {
        Model.Chain c = store.chains.get(chainId);
        if (c == null) return null;
        return chainJson(store, c);
    }
}
