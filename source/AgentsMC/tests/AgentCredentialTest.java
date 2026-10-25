package dev.agentsmc;

public final class AgentCredentialTest {
    public static void main(String[] args) {
        String a = AgentCredential.mint();
        String b = AgentCredential.mint();
        if (a.equals(b)) throw new AssertionError("Credentials must be unique");
        if (!AgentCredential.matches(AgentCredential.digest(a), AgentCredential.digest(a)))
            throw new AssertionError("Owner credential must authenticate");
        if (AgentCredential.matches(AgentCredential.digest(a), AgentCredential.digest(b)))
            throw new AssertionError("Agent A must not authenticate as agent B");
        for (String invalid : new String[] {"", "chatgpt", "amc_wrong", a + "x"}) {
            try { AgentCredential.digest(invalid); throw new AssertionError("Malformed credential accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        try { AgentCredential.matches(AgentCredential.digest(a), "broken"); throw new AssertionError("Malformed stored digest accepted"); }
        catch (IllegalStateException expected) { }
        System.out.println("PASS: unique credentials, owner authentication, cross-agent rejection, malformed input rejection");
    }
}
