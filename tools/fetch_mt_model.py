#!/usr/bin/env python3
"""
Télécharge le modèle de traduction hors-ligne livré dans l'app (Firefox Translations, Mozilla,
MPL 2.0) depuis les serveurs de Firefox, et le range dans app/src/main/assets/mt/<sens> :

    python3 tools/fetch_mt_model.py            # anglais → français (en fr)
    python3 tools/fetch_mt_model.py en de      # un autre sens

Fichiers : model.bin (le modèle), lex.bin (traductions probables), vocab.spm (ou srcvocab.spm et
trgvocab.spm). Chacun est vérifié par son empreinte SHA-256. Sans ce modèle dans l'APK, l'app le
télécharge elle-même au premier usage, comme les autres sens.
"""
import hashlib, json, os, sys, urllib.request

RECORDS = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records"
CDN = "https://firefox-settings-attachments.cdn.mozilla.net/"
NAMES = {"model": "model.bin", "lex": "lex.bin", "vocab": "vocab.spm", "srcvocab": "srcvocab.spm", "trgvocab": "trgvocab.spm"}


def ver(v):
    return tuple(int(x) for x in v.replace("a", ".").replace("b", ".").split(".") if x.isdigit())


def main():
    src, tgt = (sys.argv[1:3] if len(sys.argv) > 2 else ("en", "fr"))
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "mt", src + tgt)
    with urllib.request.urlopen(f"{RECORDS}?fromLang={src}&toLang={tgt}") as r:
        recs = [x for x in json.load(r)["data"] if x.get("fromLang") == src and x.get("toLang") == tgt]
    models = [x for x in recs if x.get("fileType") == "model"]
    if not models:
        sys.exit(f"aucun modèle {src} → {tgt} chez Firefox")
    version = max(models, key=lambda x: ver(x.get("version", "0")))["version"]
    os.makedirs(out, exist_ok=True)
    for x in recs:
        name = NAMES.get(x.get("fileType"))
        if x.get("version") != version or not name:
            continue
        a = x["attachment"]
        with urllib.request.urlopen(CDN + a["location"]) as r:
            data = r.read()
        if a.get("hash") and hashlib.sha256(data).hexdigest() != a["hash"]:
            sys.exit(f"{name} : empreinte différente, téléchargement abîmé")
        if x.get("fileType") == "model" and "alphas" not in x.get("name", ""):
            sys.exit("modèle sans « alphas » : l'app suppose la précision int8shiftAlphaAll pour le modèle livré")
        with open(os.path.join(out, name), "wb") as f:
            f.write(data)
        print(f"{name} : {len(data) / 1e6:.1f} Mo")
    print(f"modèle {src} → {tgt} (version {version}) dans {os.path.normpath(out)}")


if __name__ == "__main__":
    main()
