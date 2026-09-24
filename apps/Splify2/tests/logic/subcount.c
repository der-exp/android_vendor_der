/* Счёт узлов подписки РАЗБОРОМ ДВИЖКА — эталон для SubParse (Subs.kt).
 *
 * Приложение считает пригодные узлы само (у сокета нет команды разбора подписки), и расходиться
 * с движком ему нельзя: номер узла в выходе считается среди пригодных. Поэтому стенд прогоняет
 * одни и те же тексты через этот файл — он включает sub.c и vless_proto.c движка тем же приёмом,
 * что steer/tests/submatch.c, — и через Kotlin, и сверяет числа.
 *
 * Печатает «пригодных пропущенных чужих» для файла из argv[1]. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sub.c"
#include "vless_proto.c"

int main(int argc, char **argv) {
    if (argc < 2) return 2;
    FILE *f = fopen(argv[1], "rb");
    if (!f) return 2;
    static char raw[262144], dec[262144];
    size_t n = fread(raw, 1, sizeof raw - 1, f);
    fclose(f);
    raw[n] = 0;
    const char *text = vless_sub_text(raw, n, dec, sizeof dec);
    static struct vless_node nodes[128];
    struct vless_sub_stats st;
    size_t cnt = vless_parse_sub(text, nodes, 128, &st);
    printf("%zu %zu %zu\n", cnt, st.skipped, st.foreign);
    return 0;
}
