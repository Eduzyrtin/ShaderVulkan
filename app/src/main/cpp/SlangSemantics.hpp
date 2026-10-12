#pragma once
#include <string>
#include <map>

enum class TextureSemantic {
    Original, Source, OriginalHistory, PassOutput, PassFeedback, User, Invalid
};

struct TextureSemanticAlias {
    TextureSemantic semantic;
    int index;
};

// aliasMap: nombre exacto (alias de pase o id de LUT) -> semántica.
// Se consulta primero (igual que slang_name_to_texture_semantic en slang_process.c,
// que primero busca en el name_map antes de caer al parseo de prefijo+índice).
// passBase (T45): desplazamiento de los PassOutputN / PassFeedbackN escritos con número (no los alias) cuando el pase viene de un
// preset combinado: su numeración es la de su preset de origen, y en la cadena unida empieza en passBase.
TextureSemantic resolveTextureSemantic(
    const std::string& name, int& outIndex,
    const std::map<std::string, TextureSemanticAlias>& aliasMap, int passBase = 0);
