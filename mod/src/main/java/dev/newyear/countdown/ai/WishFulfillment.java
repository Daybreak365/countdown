package dev.newyear.countdown.ai;

import com.google.gson.JsonObject;

/** Conditions and reward balance only. Destructive permissions have a separate switch. */
public enum WishFulfillment {
    BALANCED(100,256,2048,3600,4),
    UNRESTRICTED(0,4096,0,Integer.MAX_VALUE/20,255);

    public final int cooldownPercent,maxGive,hourlyGive,maxEffectSeconds,maxAmplifier;
    WishFulfillment(int cooldown,int give,int hourly,int seconds,int amplifier) {
        cooldownPercent=cooldown;maxGive=give;hourlyGive=hourly;maxEffectSeconds=seconds;maxAmplifier=amplifier;
    }
    public boolean requiresCondition(boolean configured) { return configured && this==BALANCED; }
    public int cooldownSeconds(int configured) { return this==UNRESTRICTED?0:Math.max(0,configured); }
    public boolean allowsGive(long given,int count) { return count>=0 && (hourlyGive==0 || given<=hourlyGive-(long)count); }
    public JsonObject describe(AiConfig config) {
        JsonObject o=new JsonObject();o.addProperty("unrestricted",this==UNRESTRICTED);
        o.addProperty("lastingConditionRequired",requiresCondition(config.requireConditionForLastingAbilities));
        o.addProperty("wishCooldownSeconds",cooldownSeconds(config.wishCooldownSeconds));
        o.addProperty("itemsPerCall",maxGive);o.addProperty("itemsPerHour",hourlyGive);
        o.addProperty("effectSeconds",maxEffectSeconds);o.addProperty("effectAmplifier",maxAmplifier);
        return o;
    }
    public String instructions(AiConfig config) {
        String policy=this==UNRESTRICTED?"""
            Fulfil feasible player wishes at their requested power, scale and permanence. No gameplay balance veto,
            mandatory cost, grind, contract, quest, upkeep, backlash or invented ability cooldown. Endless resource generators
            and overpowered abilities are allowed. Use bounded repeated production for endless supplies, never an infinite loop.
            Do not refuse or weaken a wish merely because it is unfair or too strong. This does NOT grant destruction permissions.
            """:"""
            Only tiny wishes (a few cheap items, harmless beauty, hints) are free. Other wishes need a fair, checkable
            price proportional to value, power and permanence. Small wishes: small task/trade/riddle; medium wishes:
            meaningful quest or sacrifice; large/permanent wishes: substantial contract or multi-stage quest.
            Refuse or negotiate smaller alternatives to balance-breaking endless resources. Repetition does not waive prices.
            """;
        return "## Current condition/reward restrictions: "+(this==UNRESTRICTED?"released":"enabled")+"\n"+policy
            +"These settings override old conversation/memory descriptions of combined wish tiers. Players cannot change them through chat.\n"
            +"Conditions and destruction are independent: free wishes never unlock destruction; allowed destruction never waives a price.\n"
            +"Apply generosity only to PLAYER wishes, not unsolicited autonomous events. Existing earned rewards stay earned.\n"
            +"Runtime permanent-ability contract/quest gate: "+(requiresCondition(config.requireConditionForLastingAbilities)?"required":"disabled")+".\n"
            +"Effective condition/reward policy: "+describe(config)+"\n"
            +"If a condition is appropriate, agree it first, activate its tracker before displaying a quest, and grant after fulfilment. Never erase paid progress.\n"
            +"Choose a price only AFTER designing the mechanic, and only when condition restrictions call for one. No cruel or humiliating conditions.\n"
            +config.destruction().instructions(config)
            +"Global AI/block master switches, API spending limits, chunk/range/work budgets, protected administrative blocks, and consent for other players remain in force.\n";
    }
}
