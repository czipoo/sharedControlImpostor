package com.czipo.sharedControlImpostor;

public class CustomObjectiveData {
    private String name;
    private String action; // "mining", "pickup", "kill"
    private String target;
    private int amount;

    public CustomObjectiveData(String name, String action, String target, int amount) {
        this.name = name;
        this.action = action;
        this.target = target;
        this.amount = amount;
    }

    public String getName() { return name; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public int getAmount() { return amount; }
}
