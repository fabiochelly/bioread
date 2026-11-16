<?php
/*
 * Bioread : synchronisation de la position de lecture, sur votre propre serveur.
 *
 * Installation : déposez ce fichier seul dans un dossier de votre site (par exemple
 * https://exemple.fr/bioread-sync/) et donnez au serveur web le droit d'écrire dans ce dossier.
 * Dans Bioread, Options › Synchronisation : l'adresse du dossier, un identifiant et un mot de
 * passe, puis « Créer le compte ». Bioread reconnaît seul ce serveur ; une adresse de serveur
 * KOReader (kosync) fonctionne toujours comme avant.
 *
 * Comptes et positions sont gardés en JSON dans bioread-data.php, à côté de ce fichier :
 *
 *   <?php exit; ?>
 *   {"users":{"lecteur":"<MD5 du mot de passe>"},
 *    "progress":{"lecteur":{"<empreinte du livre>":{"progress":"…","percentage":0.42,…}}}}
 *
 * La première ligne empêche quiconque de lire le fichier depuis le web (un .json serait
 * servi tel quel). Le reste se modifie à la main : pour ajouter un compte, une entrée
 * "identifiant": "mot de passe" dans "users" suffit, en clair ; il est remplacé par son
 * empreinte à la première connexion.
 *
 * Le premier compte se crée depuis l'app ; ensuite, la création depuis l'app est fermée
 * (personne d'autre ne peut s'inscrire sur votre serveur), sauf à passer OPEN_SIGNUP à true.
 *
 * Utilisez une adresse en https : le mot de passe circule sous forme d'empreinte MD5, comme
 * dans le protocole de KOReader, ce qui n'est pas un secret sur une connexion en clair.
 */

const OPEN_SIGNUP = false;

// ---------------------------------------------------------------------------------------------

const DATA_FILE = __DIR__ . '/bioread-data.php';
const DATA_HEAD = "<?php exit; ?>\n";
const MAX_BODY = 16384;
const NAME = '/^[A-Za-z0-9_.@-]{1,64}$/';
const DOC = '/^[A-Za-z0-9_-]{1,64}$/';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function reply(int $code, $body): void
{
    http_response_code($code);
    echo json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

/** En-tête de la requête, quelle que soit la façon dont le serveur web les transmet à PHP. */
function header_value(string $name): string
{
    $key = 'HTTP_' . strtoupper(str_replace('-', '_', $name));
    if (isset($_SERVER[$key])) return (string)$_SERVER[$key];
    if (function_exists('getallheaders')) {
        foreach (getallheaders() as $k => $v) if (strcasecmp($k, $name) === 0) return (string)$v;
    }
    return '';
}

/** Lit, modifie et réécrit les données sous verrou exclusif ; rend ce que rend [change]. */
function with_data(callable $change)
{
    $f = @fopen(DATA_FILE, 'c+');
    if ($f === false) reply(500, ['message' => "Impossible d'écrire dans le dossier du serveur (droits d'écriture ?)"]);
    flock($f, LOCK_EX);
    $raw = stream_get_contents($f);
    if (strncmp($raw, DATA_HEAD, strlen(DATA_HEAD)) === 0) $raw = substr($raw, strlen(DATA_HEAD));
    $data = json_decode(trim($raw) === '' ? '{}' : $raw, true);
    if (!is_array($data)) {
        flock($f, LOCK_UN);
        fclose($f);
        reply(500, ['message' => 'bioread-data.php : JSON illisible']);
    }
    $data += ['users' => [], 'progress' => []];
    $before = $data;
    $out = $change($data);
    if ($data !== $before) {
        ftruncate($f, 0);
        rewind($f);
        fwrite($f, DATA_HEAD . json_encode($data, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT) . "\n");
        fflush($f);
    }
    flock($f, LOCK_UN);
    fclose($f);
    return $out;
}

/**
 * Vrai si [key] (MD5 du mot de passe, envoyé par l'app) ouvre le compte [user]. Un mot de passe
 * écrit en clair à la main est accepté puis remplacé par son empreinte.
 */
function check(array &$data, string $user, string $key): bool
{
    $stored = $data['users'][$user] ?? null;
    if (!is_string($stored) || $key === '') return false;
    if (preg_match('/^[0-9a-f]{32}$/', $stored)) return hash_equals($stored, $key);
    if (!hash_equals(md5($stored), $key)) return false;
    $data['users'][$user] = $key;
    return true;
}

$action = $_GET['action'] ?? '';
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

// Présentation : c'est ainsi que Bioread reconnaît ce serveur.
if ($action === '' || $action === 'hello') reply(200, ['bioread' => 1, 'version' => 1]);

// Création d'un compte : {"username": "…", "password": "<MD5 du mot de passe>"}.
if ($action === 'create') {
    if ($method !== 'POST') reply(405, ['message' => 'Method not allowed']);
    $in = json_decode((string)file_get_contents('php://input', false, null, 0, MAX_BODY), true);
    $name = is_array($in) ? (string)($in['username'] ?? '') : '';
    $key = is_array($in) ? strtolower((string)($in['password'] ?? '')) : '';
    if (!preg_match(NAME, $name) || !preg_match('/^[0-9a-f]{32}$/', $key)) reply(400, ['message' => 'Identifiant ou mot de passe invalide']);
    $code = with_data(function (array &$data) use ($name, $key) {
        if (isset($data['users'][$name])) return 402;
        if (!OPEN_SIGNUP && count($data['users']) > 0) return 403;
        $data['users'][$name] = $key;
        return 201;
    });
    if ($code === 402) reply(402, ['message' => 'Identifiant déjà pris']);
    if ($code === 403) reply(403, ['message' => 'Inscriptions fermées : ajoutez le compte dans bioread-data.php']);
    reply(201, ['username' => $name]);
}

$user = header_value('x-auth-user');
$key = strtolower(header_value('x-auth-key'));
$ok = preg_match(NAME, $user) && with_data(function (array &$data) use ($user, $key) {
    return check($data, $user, $key);
});
if (!$ok) reply(401, ['message' => 'Unauthorized']);

if ($action === 'auth') reply(200, ['authorized' => 'OK']);

if ($action === 'progress') {
    if ($method === 'GET') {
        $doc = (string)($_GET['document'] ?? '');
        if (!preg_match(DOC, $doc)) reply(400, ['message' => 'Bad document']);
        $entry = with_data(function (array &$data) use ($user, $doc) {
            return $data['progress'][$user][$doc] ?? null;
        });
        reply(200, is_array($entry) ? ['document' => $doc] + $entry : new stdClass());
    }
    if ($method === 'POST' || $method === 'PUT') {
        $raw = file_get_contents('php://input', false, null, 0, MAX_BODY + 1);
        if ($raw === false || strlen($raw) > MAX_BODY) reply(413, ['message' => 'Too large']);
        $in = json_decode($raw, true);
        $doc = is_array($in) ? (string)($in['document'] ?? '') : '';
        if (!preg_match(DOC, $doc) || !isset($in['progress'])) reply(400, ['message' => 'Bad request']);
        $entry = [
            'progress' => mb_substr((string)$in['progress'], 0, 2048),
            'percentage' => max(0.0, min(1.0, (float)($in['percentage'] ?? 0))),
            'device' => mb_substr((string)($in['device'] ?? ''), 0, 100),
            'device_id' => mb_substr((string)($in['device_id'] ?? ''), 0, 100),
            'timestamp' => time(),
        ];
        with_data(function (array &$data) use ($user, $doc, $entry) {
            $data['progress'][$user][$doc] = $entry;
        });
        reply(200, ['document' => $doc, 'timestamp' => $entry['timestamp']]);
    }
    reply(405, ['message' => 'Method not allowed']);
}

reply(404, ['message' => 'Unknown action']);
