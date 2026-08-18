package com.mewcode.tool;
public enum ToolEffect { READ_ONLY_LOCAL, READ_ONLY_EXTERNAL, USER_INTERACTION, MUTATION, PROCESS, EXTERNAL_EFFECT;
 public boolean sideEffect(){return this==MUTATION||this==PROCESS||this==EXTERNAL_EFFECT;}}
