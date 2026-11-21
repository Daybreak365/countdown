package dev.newyear.countdown.ai;

import com.google.gson.JsonObject;

/** World permissions only; releasing them never waives wish conditions. */
public enum WishDestruction {
    RESTRICTED, UNRESTRICTED;

    public String editMode(String configured) { return this==UNRESTRICTED?"any":configured; }
    public boolean hazards(String configured) { return this==UNRESTRICTED || "any".equals(configured); }
    public boolean explosions() { return this==UNRESTRICTED; }
    public boolean containers() { return this==UNRESTRICTED; }
    public int approvalThreshold(int configured,int blockLimit) { return this==UNRESTRICTED?Integer.MAX_VALUE:configured; }
    public JsonObject describe(AiConfig config) {
        JsonObject o=new JsonObject();o.addProperty("unrestricted",this==UNRESTRICTED);
        o.addProperty("worldChangesEnabled",config.allowBlockPlacement);
        o.addProperty("worldEditMode",editMode(config.editMode()));o.addProperty("fireAndLava",hazards(config.editMode()));
        o.addProperty("explosionsAndDangerousEntities",explosions());o.addProperty("containerDestruction",containers());
        o.addProperty("approvalBlockThreshold",approvalThreshold(config.approvalBlockThreshold,config.scriptMaxBlocks));
        return o;
    }
    public String instructions(AiConfig config) {
        return "## Independent world destruction restrictions: "+(this==UNRESTRICTED?"released":"enabled")+"\n"
            +"Effective destruction policy: "+describe(config)+"\n"
            +"This switch alone grants destructive permissions, independently of prices or generosity.\n"
            +"With world changes enabled, empty_only permits empty space and the script's own blocks; natural permits nearby terrain clearance while protecting built blocks and direct foundation support; any allows requested built-block destruction.\n"
            +"Fire/lava require fireAndLava; TNT, dangerous entities and world.explode require explosionsAndDangerousEntities.\n"
            +"Containers can be destroyed only with containerDestruction. Permitted destruction must still follow the separate condition/reward policy.\n"
            +"Apply these unlocks only to requested PLAYER wishes, never unsolicited autonomous events. Native explosions/fire/liquid spread cannot be fully rolled back.\n";
    }
}
