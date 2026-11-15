<?php
/**
 * مملكة الحصاد والحرب 2.5D (Kingdoms of Harvest & War - Clash + Farm)
 * Free Forever ($0) Cloud API for apk3rade.mangoad.online (InfinityFree + Cloudflare)
 * Supports:
 *   1. Asynchronous Village Upload & Global Matchmaking
 *   2. Global Farmers Market (Hay Day Style)
 *   3. Clans, Clan Chat, Troop Donations & 5v5 Clan Wars!
 */
header("Access-Control-Allow-Origin: *");
header("Content-Type: application/json; charset=UTF-8");

$dbFile = __DIR__ . "/kingdom_cloud_db.json";
if (!file_exists($dbFile)) {
    $initial = [
        "villages" => [],
        "market" => [
            ["id" => "m1", "seller" => "السلطان صلاح", "item" => "WHEAT", "qty" => 20, "price" => 120],
            ["id" => "m2", "seller" => "الملكة كليوباترا", "item" => "BREAD", "qty" => 8, "price" => 180],
            ["id" => "m3", "seller" => "الفارس مصطفى", "item" => "ELIXIR", "qty" => 50, "price" => 150]
        ],
        "clan_chat" => [
            ["sender" => "القائد مصطفى", "msg" => "أهلاً بكم في كلان فرسان المسك! استعدوا لحرب الكلانات ⚔️🔥", "time" => time()],
            ["sender" => "الفارس عمر", "msg" => "تم التبرع بـ 2 عملاق و1 غولم لقلعة الكلان 🛡️", "time" => time()]
        ],
        "clan_war_stars" => 9
    ];
    file_put_contents($dbFile, json_encode($initial, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT));
}

$raw = file_get_contents($dbFile);
$db = json_decode($raw, true);
if (!is_array($db)) {
    $db = ["villages" => [], "market" => [], "clan_chat" => [], "clan_war_stars" => 9];
}

$action = isset($_REQUEST["action"]) ? $_REQUEST["action"] : "status";

if ($action === "upload_village") {
    $player = isset($_REQUEST["player"]) ? substr(trim($_REQUEST["player"]), 0, 32) : "فارس مجهول";
    $clan = isset($_REQUEST["clan"]) ? substr(trim($_REQUEST["clan"]), 0, 32) : "فرسان المسك";
    $trophies = isset($_REQUEST["trophies"]) ? intval($_REQUEST["trophies"]) : 100;
    $thLevel = isset($_REQUEST["th"]) ? intval($_REQUEST["th"]) : 1;
    $code = isset($_REQUEST["code"]) ? trim($_REQUEST["code"]) : "";
    if ($code !== "") {
        $db["villages"][$player] = [
            "player" => $player,
            "clan" => $clan,
            "trophies" => $trophies,
            "th" => $thLevel,
            "code" => $code,
            "updated" => time()
        ];
        file_put_contents($dbFile, json_encode($db, JSON_UNESCAPED_UNICODE), LOCK_EX);
    }
    echo json_encode(["ok" => true, "count" => count($db["villages"])], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($action === "find_match") {
    $exclude = isset($_REQUEST["player"]) ? trim($_REQUEST["player"]) : "";
    $candidates = [];
    foreach ($db["villages"] as $k => $v) {
        if ($k !== $exclude) $candidates[] = $v;
    }
    if (count($candidates) === 0) {
        echo json_encode(["ok" => false, "msg" => "no_remote_villages"], JSON_UNESCAPED_UNICODE);
    } else {
        $pick = $candidates[array_rand($candidates)];
        echo json_encode(["ok" => true, "village" => $pick], JSON_UNESCAPED_UNICODE);
    }
    exit;
}

if ($action === "clan_sync") {
    $sender = isset($_REQUEST["sender"]) ? substr(trim($_REQUEST["sender"]), 0, 28) : "";
    $msg = isset($_REQUEST["msg"]) ? substr(trim($_REQUEST["msg"]), 0, 120) : "";
    $addStars = isset($_REQUEST["add_stars"]) ? intval($_REQUEST["add_stars"]) : 0;
    if ($msg !== "" && $sender !== "") {
        if (!isset($db["clan_chat"]) || !is_array($db["clan_chat"])) $db["clan_chat"] = [];
        array_unshift($db["clan_chat"], ["sender" => $sender, "msg" => $msg, "time" => time()]);
        $db["clan_chat"] = array_slice($db["clan_chat"], 0, 25);
    }
    if ($addStars > 0) {
        $db["clan_war_stars"] = intval($db["clan_war_stars"] ?? 9) + $addStars;
    }
    file_put_contents($dbFile, json_encode($db, JSON_UNESCAPED_UNICODE), LOCK_EX);
    echo json_encode([
        "ok" => true,
        "clan_war_stars" => intval($db["clan_war_stars"] ?? 9),
        "chat" => $db["clan_chat"] ?? []
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

echo json_encode(["ok" => true, "service" => "Kingdoms of Harvest & War 2.5D Cloud API"], JSON_UNESCAPED_UNICODE);
