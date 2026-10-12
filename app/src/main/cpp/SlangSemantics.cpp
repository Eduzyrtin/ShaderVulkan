#include "SlangSemantics.hpp"
#include <cstdlib>
#include <cstring>

static const char* kNames[] = {"Original","Source","OriginalHistory","PassOutput","PassFeedback","User"};
static bool isArrayed(TextureSemantic s) {
    return s == TextureSemantic::OriginalHistory || s == TextureSemantic::PassOutput ||
           s == TextureSemantic::PassFeedback || s == TextureSemantic::User;
}

TextureSemantic resolveTextureSemantic(const std::string& name, int& outIndex,
    const std::map<std::string, TextureSemanticAlias>& aliasMap, int passBase) {
    outIndex = 0;
    auto it = aliasMap.find(name);
    if (it != aliasMap.end()) { outIndex = it->second.index; return it->second.semantic; }

    for (int i = 0; i < 6; i++) {
        size_t len = strlen(kNames[i]);
        if (name.compare(0, len, kNames[i]) == 0) {
            TextureSemantic sem = (TextureSemantic)i;
            if (!isArrayed(sem)) {
                if (name.size() == len) return sem; // match exacto, no arrayado
                continue; // "Sourcexyz" no es Source
            }
            std::string rest = name.substr(len);
            if (rest.empty()) { outIndex = 0; return sem; }
            char* end = nullptr;
            long idx = strtol(rest.c_str(), &end, 10);
            if (end && *end == '\0') {
                outIndex = (int)idx;
                if (sem == TextureSemantic::PassOutput || sem == TextureSemantic::PassFeedback) outIndex += passBase;
                return sem;
            }
        }
    }
    return TextureSemantic::Invalid;
}
