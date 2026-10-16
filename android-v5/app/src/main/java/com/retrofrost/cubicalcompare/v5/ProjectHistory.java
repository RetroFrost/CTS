package com.retrofrost.cubicalcompare.v5;
import java.util.ArrayList;
/** Bounded project-level undo/redo, including card order, artwork and thumbnail edits. */
public final class ProjectHistory {
    private final ArrayList<String> states=new ArrayList<>();
    private int cursor=-1;
    private long chars;
    public void capture(String json){
        if(cursor>=0 && states.get(cursor).equals(json))return;
        while(states.size()>cursor+1)chars-=states.remove(states.size()-1).length();
        states.add(json);chars+=json.length();cursor++;
        while(states.size()>1 && (states.size()>30 || chars>24L*1024*1024)){
            chars-=states.remove(0).length();cursor--;
        }
    }
    public boolean canUndo(){return cursor>0;}
    public boolean canRedo(){return cursor+1<states.size();}
    public String undo(){return canUndo()?states.get(--cursor):null;}
    public String redo(){return canRedo()?states.get(++cursor):null;}
}
