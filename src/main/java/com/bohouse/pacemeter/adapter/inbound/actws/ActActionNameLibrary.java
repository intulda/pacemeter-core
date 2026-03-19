package com.bohouse.pacemeter.adapter.inbound.actws;

import com.bohouse.pacemeter.core.model.ActionNameLibrary;

final class ActActionNameLibrary {

    private ActActionNameLibrary() {
    }

    static String resolve(int skillId, String rawSkillName) {
        if (!isPlaceholder(rawSkillName)) {
            return rawSkillName;
        }
        String resolved = ActionNameLibrary.resolveKnown(skillId);
        return resolved.isBlank() ? rawSkillName : resolved;
    }

    private static boolean isPlaceholder(String rawSkillName) {
        if (rawSkillName == null || rawSkillName.isBlank()) {
            return true;
        }
        if (!rawSkillName.startsWith("Player")) {
            return false;
        }
        for (int i = "Player".length(); i < rawSkillName.length(); i++) {
            if (!Character.isDigit(rawSkillName.charAt(i))) {
                return false;
            }
        }
        return rawSkillName.length() > "Player".length();
    }
}
