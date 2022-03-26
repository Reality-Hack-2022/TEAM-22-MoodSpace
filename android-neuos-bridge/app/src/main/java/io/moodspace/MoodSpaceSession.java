package io.moodspace;

import com.google.firebase.database.DatabaseReference;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


public class MoodSpaceSession {

    static final String TAG = "MoodSpace";

    public String id = "";
    public String agent = "";
    public String startTime = "";
    public Map<Integer, Float> avg_motion = new HashMap();
    public Map<Integer, Float> focus = new HashMap();
    public Map<Integer, Float> zone_state = new HashMap();
    public Map<Integer, Float> heart_rate = new HashMap();

    public MoodSpaceSession() {
        // Default constructor required for calls to DataSnapshot.getValue(User.class)
    }

    public static MoodSpaceSession create(DatabaseReference database) {
        MoodSpaceSession session = new MoodSpaceSession();
        session.id = String.valueOf(UUID.randomUUID());
        database.child("sessions").child(session.id).setValue(session);
        return session;
    }

}