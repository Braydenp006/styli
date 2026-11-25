# Vendored files

Everything in this folder that is not Styli's own code was downloaded once from
the npm registry, checked against the integrity hash npm publishes for the
package, and copied here so the hub never loads anything from the internet
(the page's Content-Security-Policy would forbid it anyway).

| Package | Version | Licence | Used for | Source and integrity |
| --- | --- | --- | --- | --- |
| katex | 0.19.1 | MIT | `vendor/katex/` (math), without its .woff and .ttf fallbacks | https://registry.npmjs.org/katex/-/katex-0.19.1.tgz<br>`sha512-jG8uKntjSEcm2EpQOh0XturpPuuC9r3Sq5HLHdsFdWZT27E672SF19QEi+of0/YeOy15lDgRhKvr5614uRrEfA==` |
| @fontsource/inter | 5.3.0 | OFL-1.1 | `fonts/inter-*` (Latin and Latin-ext, 400/500/600) | https://registry.npmjs.org/@fontsource/inter/-/inter-5.3.0.tgz<br>`sha512-RofMylZmjlJEfELXeNHFWBRcSs75rGU/6bV2S2jfnvv/3rPXPGe0LgUJTklcHZ9lM4OZmAVFhcJPnACfb91A3g==` |
| @fontsource/marcellus | 5.3.0 | OFL-1.1 | `fonts/marcellus-*` (400) | https://registry.npmjs.org/@fontsource/marcellus/-/marcellus-5.3.0.tgz<br>`sha512-cMnanjpiPIC7Rq4GXIA/Zal5WLSoDV5Mc681tMkWsWfKHXnRnLGlhuGeBnLF6HrqtFkiSdiuy4NCSPfOrz266w==` |
| @fontsource/jetbrains-mono | 5.3.0 | OFL-1.1 | `fonts/jetbrains-mono-*` (400) | https://registry.npmjs.org/@fontsource/jetbrains-mono/-/jetbrains-mono-5.3.0.tgz<br>`sha512-fqDfB5I9f1p1TV486aUgB9t8zP84P0O1FtQR5Ol9vjwPy+S+EIGlVYm1cvj2W5shcZMTg2nZFdVMoH5wFu8a1A==` |

Licence texts are in `licenses/`. The three font families are SIL OFL 1.1 and
KaTeX is MIT.

## Updating

Fetch the new tarball from the URL pattern above, verify its `sha512` against
`https://registry.npmjs.org/<package>/<version>` (`dist.integrity`), unpack it
in an empty folder (never in the repo), copy the same files, and update this
table.
