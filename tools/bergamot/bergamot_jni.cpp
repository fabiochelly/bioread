// Pont JNI entre Bioread (app.bioread.Bergamot) et le moteur de traduction hors-ligne de Firefox
// (bergamot-translator, Marian). Les textes passent en UTF-8 dans des tableaux d'octets : la
// conversion « UTF-8 modifié » de JNI abîmerait les caractères hors du plan de base.
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <future>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "common/io.h"
#include "translator/definitions.h"
#include "translator/parser.h"
#include "translator/response.h"
#include "translator/service.h"

using namespace marian::bergamot;

namespace {

// Deux fils de traduction (chaque fil garde sa copie du graphe de calcul : au-delà, la mémoire
// grimpe plus vite que le gain).
size_t workers() { return std::max(1u, std::min(2u, std::thread::hardware_concurrency())); }

AsyncService *service() {
  // Un seul service pour toute l'app.
  static AsyncService *s = [] {
    AsyncService::Config c;
    c.numWorkers = workers();
    c.cacheSize = 0;
    c.logger.level = "off";
    return new AsyncService(c);
  }();
  return s;
}

struct Holder {
  std::shared_ptr<TranslationModel> model;
};

std::string bytes(JNIEnv *env, jbyteArray a) {
  jsize n = env->GetArrayLength(a);
  std::string s(static_cast<size_t>(n), '\0');
  env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
  return s;
}

jobjectArray results(JNIEnv *env, std::vector<std::string> &out) {
  jclass cls = env->FindClass("[B");
  jobjectArray arr = env->NewObjectArray(static_cast<jsize>(out.size()), cls, nullptr);
  for (size_t i = 0; i < out.size(); i++) {
    jbyteArray b = env->NewByteArray(static_cast<jsize>(out[i].size()));
    env->SetByteArrayRegion(b, 0, static_cast<jsize>(out[i].size()), reinterpret_cast<const jbyte *>(out[i].data()));
    env->SetObjectArrayElement(arr, static_cast<jsize>(i), b);
    env->DeleteLocalRef(b);
  }
  return arr;
}

void fail(JNIEnv *env, const char *what) {
  env->ThrowNew(env->FindClass("java/lang/RuntimeException"), what);
}

// Traduit chaque texte (un paragraphe) ; [second] non nul : en passant par l'anglais.
jobjectArray run(JNIEnv *env, jlong first, jlong second, jobjectArray texts) {
  try {
    auto *a = reinterpret_cast<Holder *>(first);
    auto *b = reinterpret_cast<Holder *>(second);
    jsize n = env->GetArrayLength(texts);
    std::vector<std::future<Response>> pending;
    for (jsize i = 0; i < n; i++) {
      auto arr = static_cast<jbyteArray>(env->GetObjectArrayElement(texts, i));
      std::string src = bytes(env, arr);
      env->DeleteLocalRef(arr);
      auto promise = std::make_shared<std::promise<Response>>();
      pending.push_back(promise->get_future());
      auto done = [promise](Response &&r) { promise->set_value(std::move(r)); };
      ResponseOptions opt;
      if (b) service()->pivot(a->model, b->model, std::move(src), done, opt);
      else service()->translate(a->model, std::move(src), done, opt);
    }
    std::vector<std::string> out;
    for (auto &f : pending) out.push_back(f.get().target.text);
    return results(env, out);
  } catch (const std::exception &e) {
    fail(env, e.what());
    return nullptr;
  }
}

}  // namespace

// Lit [length] octets à partir de [offset] dans [fd] (un fichier, ou un fichier rangé sans
// compression dans l'APK), dans une mémoire alignée comme le veut Marian.
AlignedMemory readAligned(int fd, jlong offset, jlong length, size_t alignment) {
  AlignedMemory m(static_cast<size_t>(length), alignment);
  size_t done = 0;
  while (done < static_cast<size_t>(length)) {
    ssize_t n = pread(fd, m.begin() + done, static_cast<size_t>(length) - done, offset + static_cast<off_t>(done));
    if (n <= 0) throw std::runtime_error("lecture du modèle impossible");
    done += static_cast<size_t>(n);
  }
  return m;
}

// Modèle chargé depuis la mémoire : [fds], [offsets], [lengths] désignent, dans l'ordre, le modèle,
// la liste des traductions probables, le vocabulaire source et le vocabulaire cible (le même
// fichier pour les deux le plus souvent : il n'est alors lu qu'une fois).
extern "C" JNIEXPORT jlong JNICALL Java_app_bioread_Bergamot_load(JNIEnv *env, jobject, jstring yaml, jintArray fds,
                                                                   jlongArray offsets, jlongArray lengths) {
  const char *c = env->GetStringUTFChars(yaml, nullptr);
  std::string cfg(c);
  env->ReleaseStringUTFChars(yaml, c);
  try {
    std::vector<jint> fd(4);
    std::vector<jlong> off(4), len(4);
    env->GetIntArrayRegion(fds, 0, 4, fd.data());
    env->GetLongArrayRegion(offsets, 0, 4, off.data());
    env->GetLongArrayRegion(lengths, 0, 4, len.data());
    MemoryBundle bundle;
    bundle.models.push_back(readAligned(fd[0], off[0], len[0], 256));
    bundle.shortlist = readAligned(fd[1], off[1], len[1], 64);
    auto src = std::make_shared<AlignedMemory>(readAligned(fd[2], off[2], len[2], 64));
    bool same = fd[3] == fd[2] && off[3] == off[2] && len[3] == len[2];
    auto trg = same ? src : std::make_shared<AlignedMemory>(readAligned(fd[3], off[3], len[3], 64));
    bundle.vocabs = {src, trg};
    auto options = parseOptionsFromString(cfg, /*validate=*/false);
    // Architecture du modèle (dimensions, couches) : rangée dans le modèle même, que Marian ne relit
    // que lorsqu'il ouvre un fichier ; chargé depuis la mémoire, il faut la lui fournir.
    YAML::Node arch;
    marian::io::getYamlFromModel(arch, "special:model.yml", bundle.models[0].begin());
    options->merge(arch, /*overwrite=*/false);
    service();  // le service d'abord : ses fils doivent exister quand le modèle est créé
    auto model = std::make_shared<TranslationModel>(options, std::move(bundle), workers());
    return reinterpret_cast<jlong>(new Holder{model});
  } catch (const std::exception &e) {
    fail(env, e.what());
    return 0;
  }
}

extern "C" JNIEXPORT void JNICALL Java_app_bioread_Bergamot_free(JNIEnv *, jobject, jlong model) {
  delete reinterpret_cast<Holder *>(model);
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_app_bioread_Bergamot_translate(JNIEnv *env, jobject, jlong model,
                                                                             jobjectArray texts) {
  return run(env, model, 0, texts);
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_app_bioread_Bergamot_pivot(JNIEnv *env, jobject, jlong first,
                                                                         jlong second, jobjectArray texts) {
  return run(env, first, second, texts);
}
