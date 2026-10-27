package org.example.com.sRExpansion;

public class Threshold {
    private final double score;
    private final String command;
    private final boolean console;
    public Threshold(double score, String command, boolean console) {
        this.score = score;
        this.command = command;
        this.console = console;
    }
    public double getScore() { return score; }
    public String getCommand() { return command; }
    public boolean isConsole() { return console; }
}