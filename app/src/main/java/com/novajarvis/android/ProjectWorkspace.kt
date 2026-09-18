package com.novajarvis.android

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class JarvisProjectType {
    WEBSITE,
    GAME_2D,
    GAME_3D,
    UNKNOWN
}

data class JarvisProject(
    val id: String,
    val name: String,
    val type: JarvisProjectType,
    val directory: File
)

class ProjectWorkspace(
    private val context: Context
) {

    companion object {
        private const val PREFS_NAME = "jarvis_builder"
        private const val CURRENT_PROJECT_ID = "current_project_id"
        private const val CURRENT_PROJECT_TYPE = "current_project_type"
        private const val CURRENT_PROJECT_NAME = "current_project_name"
        private const val PROJECT_INFO_FILE = "jarvis.project"
        private const val INDEX_FILE = "index.html"
    }

    private val prefs by lazy {
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    private val projectsRoot by lazy {
        File(
            context.filesDir,
            "jarvis_projects"
        ).apply {
            mkdirs()
        }
    }

    // ============================================================
    // PROJECT TYPE DETECTION
    // ============================================================

    fun detectProjectType(
        request: String
    ): JarvisProjectType {

        val text =
            request.lowercase(Locale.getDefault()).trim()

        val explicit3D = listOf(
            "3d game",
            "3-d game",
            "three dimensional game",
            "three-dimensional game",
            "webgl game",
            "three.js game",
            "threejs game"
        )

        val explicit2D = listOf(
            "2d game",
            "2-d game",
            "two dimensional game",
            "two-dimensional game",
            "canvas game",
            "platformer",
            "platform game",
            "top down game",
            "top-down game",
            "side scroller",
            "side-scroller"
        )

        if (explicit3D.any { text.contains(it) }) {
            return JarvisProjectType.GAME_3D
        }

        if (explicit2D.any { text.contains(it) }) {
            return JarvisProjectType.GAME_2D
        }

        if (text.contains("game")) {
            return JarvisProjectType.UNKNOWN
        }

        val websites = listOf(
            "website",
            "web site",
            "webpage",
            "web page",
            "landing page",
            "portfolio site",
            "business site"
        )

        if (websites.any { text.contains(it) }) {
            return JarvisProjectType.WEBSITE
        }

        return JarvisProjectType.UNKNOWN
    }

    // ============================================================
    // ROUTING
    // ============================================================

    fun isNewBuildRequest(
        request: String
    ): Boolean {

        val text =
            request.lowercase(Locale.getDefault()).trim()

        val current =
            getCurrentProject()

        /*
         * Explicit NEW instructions take priority.
         */
        val explicitNew = listOf(
            "create a new",
            "create another",
            "build a new",
            "build another",
            "make a new",
            "make another",
            "start a new",
            "start another",
            "new website",
            "new web site",
            "new 2d game",
            "new 3d game",
            "another website",
            "another 2d game",
            "another 3d game",
            "separate website",
            "separate game",
            "separate project"
        )

        if (
            current != null &&
            explicitNew.any { text.contains(it) }
        ) {
            return true
        }

        val editIndicators = listOf(
            "current website",
            "current web site",
            "current webpage",
            "current web page",
            "current game",
            "current project",
            "existing website",
            "existing game",
            "existing project",
            "this website",
            "this game",
            "this project",
            "my website",
            "my game",
            "my project",
            "improve",
            "update",
            "edit",
            "modify",
            "fix",
            "continue",
            "upgrade",
            "change",
            "add to",
            "remove from"
        )

        if (
            current != null &&
            editIndicators.any { text.contains(it) }
        ) {
            return false
        }

        if (current != null) {
            return false
        }

        val creationWords = listOf(
            "build",
            "create",
            "make",
            "develop",
            "generate",
            "start"
        )

        val projectWords = listOf(
            "website",
            "web site",
            "webpage",
            "web page",
            "game",
            "2d",
            "3d",
            "three.js",
            "threejs",
            "webgl"
        )

        return creationWords.any {
            text.contains(it)
        } && projectWords.any {
            text.contains(it)
        }
    }

    fun isProjectEditRequest(
        request: String
    ): Boolean {

        if (getCurrentProject() == null) {
            return false
        }

        val text =
            request.lowercase(Locale.getDefault()).trim()

        val explicitNew = listOf(
            "create a new",
            "create another",
            "build a new",
            "build another",
            "make a new",
            "make another",
            "start a new",
            "start another",
            "new website",
            "new 2d game",
            "new 3d game",
            "another website",
            "another 2d game",
            "another 3d game",
            "separate project"
        )

        if (explicitNew.any { text.contains(it) }) {
            return false
        }

        val editWords = listOf(
            "change",
            "edit",
            "update",
            "improve",
            "fix",
            "add",
            "remove",
            "continue",
            "upgrade",
            "replace",
            "increase",
            "decrease",
            "bigger",
            "smaller",
            "faster",
            "slower",
            "better",
            "more",
            "less",
            "make it",
            "make the",
            "make this",
            "give it",
            "put",
            "move",
            "resize",
            "rework",
            "redesign"
        )

        if (editWords.any { text.contains(it) }) {
            return true
        }

        return listOf(
            "current project",
            "current website",
            "current game",
            "this project",
            "this website",
            "this game",
            "my project",
            "my website",
            "my game"
        ).any {
            text.contains(it)
        }
    }

    // ============================================================
    // CREATE PROJECT
    // ============================================================

    fun createProject(
        requestedName: String,
        type: JarvisProjectType
    ): JarvisProject {

        require(type != JarvisProjectType.UNKNOWN)

        val safeName =
            sanitizeProjectName(requestedName)

        val id =
            UUID.randomUUID()
                .toString()
                .take(8)

        val typeFolder =
            when (type) {
                JarvisProjectType.WEBSITE -> "websites"
                JarvisProjectType.GAME_2D -> "games_2d"
                JarvisProjectType.GAME_3D -> "games_3d"
                JarvisProjectType.UNKNOWN -> "unknown"
            }

        val directory =
            File(
                projectsRoot,
                "$typeFolder/${safeName}_$id"
            )

        directory.mkdirs()

        File(
            directory,
            "backups"
        ).mkdirs()

        val project =
            JarvisProject(
                id = id,
                name = safeName,
                type = type,
                directory = directory
            )

        writeProjectInfo(project)
        setCurrentProject(project)

        /*
         * Give every game a guaranteed working starting point.
         */
        when (type) {

            JarvisProjectType.GAME_2D -> {

                File(
                    directory,
                    INDEX_FILE
                ).writeText(
                    create2DGameFoundation(
                        displayName(safeName)
                    )
                )
            }

            JarvisProjectType.GAME_3D -> {

                File(
                    directory,
                    INDEX_FILE
                ).writeText(
                    create3DGameFoundation(
                        displayName(safeName)
                    )
                )
            }

            else -> Unit
        }

        return project
    }

    fun setCurrentProject(
        project: JarvisProject
    ) {

        prefs.edit()
            .putString(
                CURRENT_PROJECT_ID,
                project.id
            )
            .putString(
                CURRENT_PROJECT_TYPE,
                project.type.name
            )
            .putString(
                CURRENT_PROJECT_NAME,
                project.name
            )
            .apply()
    }

    fun getCurrentProject():
        JarvisProject? {

        val id =
            prefs.getString(
                CURRENT_PROJECT_ID,
                null
            ) ?: return null

        val typeName =
            prefs.getString(
                CURRENT_PROJECT_TYPE,
                null
            ) ?: return null

        val name =
            prefs.getString(
                CURRENT_PROJECT_NAME,
                null
            ) ?: return null

        val type =
            try {
                JarvisProjectType.valueOf(typeName)
            } catch (_: Exception) {
                return null
            }

        return findProject(
            id,
            type,
            name
        )
    }

    private fun findProject(
        id: String,
        type: JarvisProjectType,
        name: String
    ): JarvisProject? {

        val folder =
            when (type) {
                JarvisProjectType.WEBSITE -> "websites"
                JarvisProjectType.GAME_2D -> "games_2d"
                JarvisProjectType.GAME_3D -> "games_3d"
                JarvisProjectType.UNKNOWN -> return null
            }

        val directory =
            File(
                projectsRoot,
                "$folder/${name}_$id"
            )

        if (!directory.exists()) {
            return null
        }

        return JarvisProject(
            id,
            name,
            type,
            directory
        )
    }

    // ============================================================
    // PROJECT FILES
    // ============================================================

    fun saveMainFile(
        project: JarvisProject,
        html: String
    ): File {

        require(html.isNotBlank())

        backupCurrentVersion(project)

        val index =
            File(
                project.directory,
                INDEX_FILE
            )

        index.writeText(html)

        setCurrentProject(project)

        return index
    }

    fun readMainFile(
        project: JarvisProject
    ): String? {

        val file =
            File(
                project.directory,
                INDEX_FILE
            )

        if (!file.exists()) {
            return null
        }

        return try {
            file.readText()
        } catch (_: Exception) {
            null
        }
    }

    fun currentMainFile():
        File? {

        val project =
            getCurrentProject()
                ?: return null

        return File(
            project.directory,
            INDEX_FILE
        ).takeIf {
            it.exists() &&
                it.length() > 0L
        }
    }

    // ============================================================
    // BACKUPS
    // ============================================================

    private fun backupCurrentVersion(
        project: JarvisProject
    ) {

        val current =
            File(
                project.directory,
                INDEX_FILE
            )

        if (
            !current.exists() ||
            current.length() == 0L
        ) {
            return
        }

        val backupDirectory =
            File(
                project.directory,
                "backups"
            ).apply {
                mkdirs()
            }

        val formatter =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.US
            )

        val backup =
            File(
                backupDirectory,
                "index_${formatter.format(Date())}.html"
            )

        current.copyTo(
            backup,
            overwrite = false
        )

        trimOldBackups(
            backupDirectory
        )
    }

    private fun trimOldBackups(
        directory: File
    ) {

        val backups =
            directory
                .listFiles()
                ?.filter {
                    it.isFile &&
                        it.extension.equals(
                            "html",
                            true
                        )
                }
                ?.sortedByDescending {
                    it.lastModified()
                }
                ?: return

        backups
            .drop(10)
            .forEach {
                try {
                    it.delete()
                } catch (_: Exception) {
                }
            }
    }

    // ============================================================
    // AI OUTPUT EXTRACTION
    // ============================================================

    fun extractGeneratedHtml(
        output: String
    ): String? {

        val tagged =
            Regex(
                "(?s)<JARVIS_FILE>\\s*(.*?)\\s*</JARVIS_FILE>",
                RegexOption.IGNORE_CASE
            )
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

        if (!tagged.isNullOrBlank()) {
            return tagged
        }

        val fenced =
            Regex(
                "(?s)```(?:html)?\\s*(.*?)\\s*```",
                RegexOption.IGNORE_CASE
            )
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

        if (!fenced.isNullOrBlank()) {
            return fenced
        }

        val doctype =
            output.indexOf(
                "<!doctype",
                ignoreCase = true
            )

        if (doctype >= 0) {
            return output
                .substring(doctype)
                .trim()
        }

        val html =
            output.indexOf(
                "<html",
                ignoreCase = true
            )

        if (html >= 0) {
            return output
                .substring(html)
                .trim()
        }

        return null
    }

    // ============================================================
    // VALIDATION
    // ============================================================

    fun validateProject(
        project: JarvisProject,
        html: String
    ): List<String> {

        val problems =
            mutableListOf<String>()

        val lower =
            html.lowercase(
                Locale.getDefault()
            )

        if (
            !lower.contains("<html") &&
            !lower.contains("<!doctype")
        ) {
            problems.add(
                "Missing HTML document structure."
            )
        }

        if (!lower.contains("<body")) {
            problems.add(
                "Missing BODY element."
            )
        }

        if (!lower.contains("</html>")) {
            problems.add(
                "Missing closing HTML element."
            )
        }

        when (project.type) {

            JarvisProjectType.WEBSITE -> {

                if (
                    !lower.contains(
                        "<meta name=\"viewport\""
                    ) &&
                    !lower.contains(
                        "<meta name='viewport'"
                    )
                ) {
                    problems.add(
                        "Website is missing a mobile viewport."
                    )
                }
            }

            JarvisProjectType.GAME_2D -> {

                if (!lower.contains("<canvas")) {
                    problems.add(
                        "2D game has no Canvas."
                    )
                }

                if (!lower.contains("<script")) {
                    problems.add(
                        "2D game has no game script."
                    )
                }

                if (
                    !lower.contains(
                        "requestanimationframe"
                    )
                ) {
                    problems.add(
                        "2D game has no animation/game loop."
                    )
                }
            }

            JarvisProjectType.GAME_3D -> {

                val hasThree =
                    lower.contains(
                        "three.module"
                    ) ||
                    lower.contains(
                        "three.min"
                    ) ||
                    lower.contains(
                        "from 'three'"
                    ) ||
                    lower.contains(
                        "from \"three\""
                    )

                if (!hasThree) {
                    problems.add(
                        "3D game has no Three.js engine."
                    )
                }

                if (
                    !lower.contains(
                        "requestanimationframe"
                    )
                ) {
                    problems.add(
                        "3D game has no animation/game loop."
                    )
                }
            }

            JarvisProjectType.UNKNOWN -> {

                problems.add(
                    "Project type is unknown."
                )
            }
        }

        return problems
    }

    fun isFatalValidationProblem(
        problem: String
    ): Boolean {

        return problem.contains(
            "Missing HTML",
            ignoreCase = true
        ) ||
            problem.contains(
                "Missing BODY",
                ignoreCase = true
            ) ||
            problem.contains(
                "Missing closing HTML",
                ignoreCase = true
            ) ||
            problem.contains(
                "no Canvas",
                ignoreCase = true
            ) ||
            problem.contains(
                "no game script",
                ignoreCase = true
            ) ||
            problem.contains(
                "no animation/game loop",
                ignoreCase = true
            ) ||
            problem.contains(
                "no Three.js",
                ignoreCase = true
            )
    }

    // ============================================================
    // GUARANTEED 2D FOUNDATION
    // ============================================================

    fun create2DGameFoundation(
        title: String = "Jarvis 2D Game"
    ): String {

        val safeTitle =
            escapeHtml(title)

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>$safeTitle</title>
<style>
*{box-sizing:border-box}
html,body{margin:0;background:#050b12;color:#fff;font-family:Arial,sans-serif;overscroll-behavior:none}
body{min-height:100%;display:flex;justify-content:center;padding:12px;touch-action:manipulation}
#gameShell{width:min(100%,760px)}
#top{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:8px}
#title{font-weight:800;font-size:18px}
#hud{font-weight:700}
#wrap{position:relative;width:100%;background:#081521;border:1px solid #1f5168;border-radius:14px;overflow:hidden}
canvas{display:block;width:100%;height:auto;aspect-ratio:16/10;background:#071019;touch-action:none}
#message{position:absolute;left:12px;right:12px;top:12px;text-align:center;pointer-events:none;font-weight:700}
#controls{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:10px}
button{min-height:52px;border:1px solid #39d9ff;border-radius:12px;background:#0b1c29;color:#fff;font-size:18px;font-weight:800}
#restart{width:100%;margin-top:8px}
</style>
</head>
<body>

<div id="gameShell">

<div id="top">
<div id="title">$safeTitle</div>
<div id="hud">Score: <span id="score">0</span></div>
</div>

<div id="wrap">
<canvas id="game" width="800" height="500"></canvas>
<div id="message">Collect the yellow coins</div>
</div>

<div id="controls">
<button id="left" type="button">◀</button>
<button id="up" type="button">▲</button>
<button id="right" type="button">▶</button>
<button id="down" type="button">▼</button>
<button id="action" type="button">ACTION</button>
<button id="pause" type="button">PAUSE</button>
</div>

<button id="restart" type="button">RESTART</button>

</div>

<script>
const canvas=document.getElementById('game');
const ctx=canvas.getContext('2d');
const scoreEl=document.getElementById('score');
const messageEl=document.getElementById('message');

const keys={
 left:false,
 right:false,
 up:false,
 down:false
};

let score=0;
let paused=false;
let last=performance.now();

const player={
 x:100,
 y:250,
 r:20,
 speed:260
};

const coin={
 x:500,
 y:250,
 r:13
};

function randomCoin(){
 coin.x=coin.r+Math.random()*(canvas.width-coin.r*2);
 coin.y=coin.r+Math.random()*(canvas.height-coin.r*2);
}

function resetGame(){
 score=0;
 scoreEl.textContent=score;

 player.x=100;
 player.y=250;

 paused=false;

 messageEl.textContent='Collect the yellow coins';

 randomCoin();
}

function update(dt){

 if(paused){
  return;
 }

 let dx=0;
 let dy=0;

 if(keys.left)dx-=1;
 if(keys.right)dx+=1;
 if(keys.up)dy-=1;
 if(keys.down)dy+=1;

 if(dx!==0||dy!==0){

  const length=
   Math.hypot(dx,dy)||1;

  player.x+=
   dx/length*
   player.speed*
   dt;

  player.y+=
   dy/length*
   player.speed*
   dt;
 }

 player.x=
  Math.max(
   player.r,
   Math.min(
    canvas.width-player.r,
    player.x
   )
  );

 player.y=
  Math.max(
   player.r,
   Math.min(
    canvas.height-player.r,
    player.y
   )
  );

 const distance=
  Math.hypot(
   player.x-coin.x,
   player.y-coin.y
  );

 if(distance<player.r+coin.r){

  score++;

  scoreEl.textContent=
   score;

  messageEl.textContent=
   'Nice! Score '+score;

  randomCoin();
 }
}

function draw(){

 ctx.clearRect(
  0,
  0,
  canvas.width,
  canvas.height
 );

 ctx.fillStyle='#07131d';

 ctx.fillRect(
  0,
  0,
  canvas.width,
  canvas.height
 );

 ctx.strokeStyle='#12384d';
 ctx.lineWidth=2;

 for(
  let x=0;
  x<canvas.width;
  x+=50
 ){
  ctx.beginPath();
  ctx.moveTo(x,0);
  ctx.lineTo(x,canvas.height);
  ctx.stroke();
 }

 for(
  let y=0;
  y<canvas.height;
  y+=50
 ){
  ctx.beginPath();
  ctx.moveTo(0,y);
  ctx.lineTo(canvas.width,y);
  ctx.stroke();
 }

 ctx.beginPath();
 ctx.fillStyle='#ffd43b';

 ctx.arc(
  coin.x,
  coin.y,
  coin.r,
  0,
  Math.PI*2
 );

 ctx.fill();

 ctx.beginPath();
 ctx.fillStyle='#32d7ff';

 ctx.arc(
  player.x,
  player.y,
  player.r,
  0,
  Math.PI*2
 );

 ctx.fill();
}

function gameLoop(now){

 const dt=
  Math.min(
   (now-last)/1000,
   0.05
  );

 last=now;

 update(dt);
 draw();

 requestAnimationFrame(
  gameLoop
 );
}

function bindHold(
 id,
 key
){

 const button=
  document.getElementById(id);

 button.addEventListener(
  'pointerdown',
  event=>{

   event.preventDefault();

   keys[key]=true;
  }
 );

 const release=
  event=>{

   event.preventDefault();

   keys[key]=false;
  };

 button.addEventListener(
  'pointerup',
  release
 );

 button.addEventListener(
  'pointercancel',
  release
 );

 button.addEventListener(
  'pointerleave',
  release
 );
}

bindHold('left','left');
bindHold('right','right');
bindHold('up','up');
bindHold('down','down');

document
 .getElementById('action')
 .addEventListener(
  'click',
  ()=>{
   messageEl.textContent='Action!';
  }
 );

document
 .getElementById('pause')
 .addEventListener(
  'click',
  ()=>{

   paused=!paused;

   messageEl.textContent=
    paused?
     'Paused':
     'Go!';
  }
 );

document
 .getElementById('restart')
 .addEventListener(
  'click',
  resetGame
 );

window.addEventListener(
 'keydown',
 event=>{

  if(
   event.key==='ArrowLeft'||
   event.key==='a'
  ){
   keys.left=true;
  }

  if(
   event.key==='ArrowRight'||
   event.key==='d'
  ){
   keys.right=true;
  }

  if(
   event.key==='ArrowUp'||
   event.key==='w'
  ){
   keys.up=true;
  }

  if(
   event.key==='ArrowDown'||
   event.key==='s'
  ){
   keys.down=true;
  }
 }
);

window.addEventListener(
 'keyup',
 event=>{

  if(
   event.key==='ArrowLeft'||
   event.key==='a'
  ){
   keys.left=false;
  }

  if(
   event.key==='ArrowRight'||
   event.key==='d'
  ){
   keys.right=false;
  }

  if(
   event.key==='ArrowUp'||
   event.key==='w'
  ){
   keys.up=false;
  }

  if(
   event.key==='ArrowDown'||
   event.key==='s'
  ){
   keys.down=false;
  }
 }
);

resetGame();

requestAnimationFrame(
 gameLoop
);
</script>

</body>
</html>
""".trimIndent()
    }

    // ============================================================
    // GUARANTEED 3D FOUNDATION
    // ============================================================

    fun create3DGameFoundation(
        title: String = "Jarvis 3D Game"
    ): String {

        val safeTitle =
            escapeHtml(title)

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>$safeTitle</title>

<style>
*{box-sizing:border-box}
html,body{margin:0;background:#030811;color:#fff;font-family:Arial,sans-serif;overscroll-behavior:none}
body{padding:10px}
#shell{width:min(100%,900px);margin:auto}
#top{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:8px}
#title{font-weight:800}
#hud{font-weight:700}
#game{position:relative;width:100%;aspect-ratio:16/10;background:#07111c;border:1px solid #24546b;border-radius:14px;overflow:hidden}
#game canvas{display:block;width:100%!important;height:100%!important;touch-action:none}
#status{position:absolute;left:10px;right:10px;top:10px;text-align:center;z-index:5;pointer-events:none;font-weight:700}
#controls{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:10px}
button{min-height:52px;border:1px solid #39d9ff;border-radius:12px;background:#0b1c29;color:#fff;font-size:18px;font-weight:800}
#restart{width:100%;margin-top:8px}
</style>
</head>

<body>

<div id="shell">

<div id="top">
<div id="title">$safeTitle</div>
<div id="hud">Score: <span id="score">0</span></div>
</div>

<div id="game">
<div id="status">Loading 3D engine...</div>
</div>

<div id="controls">
<button id="left" type="button">◀</button>
<button id="forward" type="button">▲</button>
<button id="right" type="button">▶</button>
<button id="back" type="button">▼</button>
<button id="action" type="button">ACTION</button>
<button id="pause" type="button">PAUSE</button>
</div>

<button id="restart" type="button">RESTART</button>

</div>

<script type="module">

const statusEl=
 document.getElementById('status');

const scoreEl=
 document.getElementById('score');

const gameEl=
 document.getElementById('game');

let THREE;

try{

 THREE=
  await import(
   'https://cdn.jsdelivr.net/npm/three@0.180.0/build/three.module.js'
  );

}catch(error){

 statusEl.textContent=
  '3D engine could not load. Check your internet connection.';

 throw error;
}

const scene=
 new THREE.Scene();

scene.background=
 new THREE.Color(
  0x07111c
 );

scene.fog=
 new THREE.Fog(
  0x07111c,
  18,
  55
 );

const camera=
 new THREE.PerspectiveCamera(
  60,
  gameEl.clientWidth/
   Math.max(
    gameEl.clientHeight,
    1
   ),
  0.1,
  100
 );

const renderer=
 new THREE.WebGLRenderer({
  antialias:true
 });

renderer.setPixelRatio(
 Math.min(
  window.devicePixelRatio||1,
  2
 )
);

renderer.setSize(
 gameEl.clientWidth,
 gameEl.clientHeight
);

gameEl.appendChild(
 renderer.domElement
);

const hemi=
 new THREE.HemisphereLight(
  0x9edfff,
  0x172014,
  2
 );

scene.add(
 hemi
);

const sun=
 new THREE.DirectionalLight(
  0xffffff,
  2
 );

sun.position.set(
 5,
 10,
 5
);

scene.add(
 sun
);

const floor=
 new THREE.Mesh(
  new THREE.PlaneGeometry(
   50,
   50
  ),
  new THREE.MeshStandardMaterial({
   color:0x163321,
   roughness:1
  })
 );

floor.rotation.x=
 -Math.PI/2;

scene.add(
 floor
);

const grid=
 new THREE.GridHelper(
  50,
  25,
  0x2a667c,
  0x24452f
 );

grid.position.y=
 0.01;

scene.add(
 grid
);

const player=
 new THREE.Mesh(
  new THREE.CapsuleGeometry(
   0.55,
   1,
   5,
   10
  ),
  new THREE.MeshStandardMaterial({
   color:0x32d7ff
  })
 );

player.position.set(
 0,
 1,
 0
);

scene.add(
 player
);

const target=
 new THREE.Mesh(
  new THREE.SphereGeometry(
   0.5,
   18,
   18
  ),
  new THREE.MeshStandardMaterial({
   color:0xffd43b,
   emissive:0x5a4300
  })
 );

scene.add(
 target
);

const keys={
 left:false,
 right:false,
 forward:false,
 back:false
};

let score=0;
let paused=false;
let previous=
 performance.now();

function moveTarget(){

 target.position.set(
  (Math.random()-0.5)*18,
  0.6,
  (Math.random()-0.5)*18
 );
}

function resetGame(){

 score=0;

 scoreEl.textContent=
  '0';

 player.position.set(
  0,
  1,
  0
 );

 paused=false;

 moveTarget();

 statusEl.textContent=
  'Collect the yellow orb';
}

function update(dt){

 if(paused){
  return;
 }

 let x=0;
 let z=0;

 if(keys.left)x-=1;
 if(keys.right)x+=1;
 if(keys.forward)z-=1;
 if(keys.back)z+=1;

 if(x!==0||z!==0){

  const length=
   Math.hypot(x,z)||1;

  const speed=6;

  player.position.x+=
   x/length*
   speed*
   dt;

  player.position.z+=
   z/length*
   speed*
   dt;
 }

 player.position.x=
  THREE.MathUtils.clamp(
   player.position.x,
   -20,
   20
  );

 player.position.z=
  THREE.MathUtils.clamp(
   player.position.z,
   -20,
   20
  );

 const dx=
  player.position.x-
  target.position.x;

 const dz=
  player.position.z-
  target.position.z;

 if(
  Math.hypot(
   dx,
   dz
  )<1.2
 ){

  score++;

  scoreEl.textContent=
   String(score);

  statusEl.textContent=
   'Nice! Score '+score;

  moveTarget();
 }

 const desired=
  new THREE.Vector3(
   player.position.x,
   7,
   player.position.z+10
  );

 camera.position.lerp(
  desired,
  0.08
 );

 camera.lookAt(
  player.position.x,
  0.8,
  player.position.z
 );
}

function animate(now){

 const dt=
  Math.min(
   (now-previous)/1000,
   0.05
  );

 previous=now;

 update(dt);

 target.rotation.y+=
  dt*2;

 renderer.render(
  scene,
  camera
 );

 requestAnimationFrame(
  animate
 );
}

function resize(){

 const width=
  gameEl.clientWidth;

 const height=
  Math.max(
   gameEl.clientHeight,
   1
  );

 camera.aspect=
  width/height;

 camera.updateProjectionMatrix();

 renderer.setSize(
  width,
  height
 );
}

function bindHold(
 id,
 key
){

 const button=
  document.getElementById(id);

 button.addEventListener(
  'pointerdown',
  event=>{

   event.preventDefault();

   keys[key]=true;
  }
 );

 const release=
  event=>{

   event.preventDefault();

   keys[key]=false;
  };

 button.addEventListener(
  'pointerup',
  release
 );

 button.addEventListener(
  'pointercancel',
  release
 );

 button.addEventListener(
  'pointerleave',
  release
 );
}

bindHold(
 'left',
 'left'
);

bindHold(
 'right',
 'right'
);

bindHold(
 'forward',
 'forward'
);

bindHold(
 'back',
 'back'
);

document
 .getElementById('action')
 .addEventListener(
  'click',
  ()=>{
   statusEl.textContent=
    'Action!';
  }
 );

document
 .getElementById('pause')
 .addEventListener(
  'click',
  ()=>{

   paused=
    !paused;

   statusEl.textContent=
    paused?
     'Paused':
     'Go!';
  }
 );

document
 .getElementById('restart')
 .addEventListener(
  'click',
  resetGame
 );

window.addEventListener(
 'keydown',
 event=>{

  if(
   event.key==='ArrowLeft'||
   event.key==='a'
  ){
   keys.left=true;
  }

  if(
   event.key==='ArrowRight'||
   event.key==='d'
  ){
   keys.right=true;
  }

  if(
   event.key==='ArrowUp'||
   event.key==='w'
  ){
   keys.forward=true;
  }

  if(
   event.key==='ArrowDown'||
   event.key==='s'
  ){
   keys.back=true;
  }
 }
);

window.addEventListener(
 'keyup',
 event=>{

  if(
   event.key==='ArrowLeft'||
   event.key==='a'
  ){
   keys.left=false;
  }

  if(
   event.key==='ArrowRight'||
   event.key==='d'
  ){
   keys.right=false;
  }

  if(
   event.key==='ArrowUp'||
   event.key==='w'
  ){
   keys.forward=false;
  }

  if(
   event.key==='ArrowDown'||
   event.key==='s'
  ){
   keys.back=false;
  }
 }
);

window.addEventListener(
 'resize',
 resize
);

resize();
resetGame();

requestAnimationFrame(
 animate
);

</script>

</body>
</html>
""".trimIndent()
    }

    // ============================================================
    // BUILDER SYSTEM PROMPTS
    // ============================================================

    fun builderSystemPrompt(
        type: JarvisProjectType
    ): String {

        val shared =
            """
You are JARVIS BUILDER.

Create and edit complete runnable projects.

CRITICAL RULES:

1. Return ONE COMPLETE index.html.
2. Never return partial code.
3. Never use TODO.
4. Never use ellipses instead of code.
5. Never omit closing HTML tags.
6. Keep generated code compact enough to finish.
7. Do not waste output tokens explaining the code.
8. Preserve working code when editing.
9. Never silently change project type.
10. Make controls mobile friendly.

Return the finished file exactly between:

<JARVIS_FILE>
FULL COMPLETE INDEX.HTML
</JARVIS_FILE>
""".trimIndent()

        val specialist =
            when (type) {

                JarvisProjectType.WEBSITE ->

                    """
PROJECT TYPE: WEBSITE

Build a complete polished responsive website.

Use complete HTML and CSS.
Use JavaScript when useful.
Include a mobile viewport.
Style buttons and navigation.
Make it look good on Android.
Avoid broken external images.
Prefer CSS, gradients, emoji or inline SVG.
""".trimIndent()

                JarvisProjectType.GAME_2D ->

                    """
PROJECT TYPE: 2D GAME

A complete working Canvas foundation is already available.

MODIFY THE FOUNDATION instead of rebuilding everything.

Keep:
- HTML5 Canvas
- requestAnimationFrame
- keyboard controls
- Android touch controls
- restart handling
- complete HTML structure

Change the gameplay, graphics, objects and rules to match the user's request.

Use generated Canvas graphics.
Do not require external images.
Never remove the animation loop.
Keep the code compact enough to finish.
""".trimIndent()

                JarvisProjectType.GAME_3D ->

                    """
PROJECT TYPE: 3D GAME

A complete working Three.js foundation is already available.

MODIFY THE FOUNDATION instead of rebuilding everything.

Keep:
- Three.js
- scene
- perspective camera
- WebGL renderer
- lighting
- requestAnimationFrame
- resize handling
- keyboard controls
- Android touch controls
- complete HTML structure

Change the world, gameplay, player and objectives to match the request.

Use generated Three.js geometry and materials.
Do not require external models or textures.
Never remove the animation loop.
Keep the code compact enough to finish.
""".trimIndent()

                JarvisProjectType.UNKNOWN ->

                    """
PROJECT TYPE UNKNOWN.

Ask whether the user wants a WEBSITE, 2D GAME, or 3D GAME.
""".trimIndent()
            }

        return """
$shared

$specialist
""".trimIndent()
    }

    // ============================================================
    // EDIT PROMPT
    // ============================================================

    fun createEditPrompt(
        project: JarvisProject,
        userRequest: String
    ): String {

        val existing =
            readMainFile(project)
                ?: when (project.type) {

                    JarvisProjectType.GAME_2D ->
                        create2DGameFoundation(
                            displayName(project.name)
                        )

                    JarvisProjectType.GAME_3D ->
                        create3DGameFoundation(
                            displayName(project.name)
                        )

                    else ->
                        ""
                }

        return """
You are editing the CURRENT EXISTING PROJECT.

PROJECT:
${project.name}

PROJECT ID:
${project.id}

LOCKED TYPE:
${project.type.name}

USER REQUEST:
$userRequest

Modify the existing code below.

IMPORTANT:

- This is NOT a new project.
- Preserve working features.
- Keep the same project type.
- Return the COMPLETE updated index.html.
- Never return only a changed section.
- Keep the result compact enough to finish.
- Never omit </body> or </html>.
- For 2D keep Canvas and requestAnimationFrame.
- For 3D keep Three.js and requestAnimationFrame.

EXISTING WORKING PROJECT:

<JARVIS_EXISTING_FILE>
$existing
</JARVIS_EXISTING_FILE>

Return the complete result inside JARVIS_FILE.
""".trimIndent()
    }

    // ============================================================
    // INITIAL GAME PROMPT
    // ============================================================

    fun createInitialGamePrompt(
        project: JarvisProject,
        userRequest: String
    ): String {

        val foundation =
            when (project.type) {

                JarvisProjectType.GAME_2D ->
                    create2DGameFoundation(
                        displayName(project.name)
                    )

                JarvisProjectType.GAME_3D ->
                    create3DGameFoundation(
                        displayName(project.name)
                    )

                else ->
                    ""
            }

        return """
BUILD THIS GAME:

$userRequest

You have a GUARANTEED WORKING FOUNDATION below.

MODIFY IT instead of rebuilding from nothing.

Keep its engine, controls, animation loop and complete HTML structure.

Keep your output compact enough to finish.

WORKING FOUNDATION:

<JARVIS_EXISTING_FILE>
$foundation
</JARVIS_EXISTING_FILE>

Return ONE COMPLETE updated index.html inside JARVIS_FILE.
""".trimIndent()
    }

    // ============================================================
    // FALLBACK + REPAIR
    // ============================================================

    fun workingFallback(
        project: JarvisProject
    ): String? {

        val existing =
            readMainFile(project)

        if (existing != null) {

            val fatal =
                validateProject(
                    project,
                    existing
                ).any {
                    isFatalValidationProblem(it)
                }

            if (!fatal) {
                return existing
            }
        }

        return when (project.type) {

            JarvisProjectType.GAME_2D ->
                create2DGameFoundation(
                    displayName(project.name)
                )

            JarvisProjectType.GAME_3D ->
                create3DGameFoundation(
                    displayName(project.name)
                )

            else ->
                null
        }
    }

    fun createRepairPrompt(
        project: JarvisProject,
        brokenHtml: String,
        problems: List<String>
    ): String {

        val fallback =
            workingFallback(project)
                .orEmpty()

        return """
REPAIR THIS PROJECT.

TYPE:
${project.type.name}

VALIDATION ERRORS:
${problems.joinToString("; ")}

The generated version was incomplete or invalid.

BROKEN VERSION:

<JARVIS_BROKEN_FILE>
$brokenHtml
</JARVIS_BROKEN_FILE>

KNOWN WORKING VERSION:

<JARVIS_EXISTING_FILE>
$fallback
</JARVIS_EXISTING_FILE>

Return ONE COMPLETE valid index.html.

IMPORTANT:

- Do not explain.
- Keep it compact.
- Finish the complete document.
- Include </body> and </html>.
- Preserve requestAnimationFrame for games.
- Preserve Canvas for 2D.
- Preserve Three.js for 3D.
- Prefer the known working version if the broken version cannot be repaired compactly.

Return the result inside JARVIS_FILE.
""".trimIndent()
    }

    // ============================================================
    // PROJECT INFO
    // ============================================================

    private fun writeProjectInfo(
        project: JarvisProject
    ) {

        File(
            project.directory,
            PROJECT_INFO_FILE
        ).writeText(
            """
id=${project.id}
name=${project.name}
type=${project.type.name}
created=${System.currentTimeMillis()}
""".trimIndent()
        )
    }

    // ============================================================
    // PROJECT NAMES
    // ============================================================

    fun suggestedProjectName(
        request: String,
        type: JarvisProjectType
    ): String {

        val called =
            Regex(
                """\b(?:called|named)\s+([a-z0-9][a-z0-9 '&-]{0,39})(?=[.!?,]|$)""",
                RegexOption.IGNORE_CASE
            )
                .find(request)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

        if (!called.isNullOrBlank()) {

            return sanitizeProjectName(
                called
            )
        }

        var cleaned =
            request.lowercase(
                Locale.getDefault()
            )

        val removable =
            listOf(
                "please",
                "jarvis",
                "can you",
                "could you",
                "build me",
                "make me",
                "create me",
                "build",
                "make",
                "create",
                "new",
                "another",
                "a website",
                "website",
                "web site",
                "webpage",
                "web page",
                "a 2d game",
                "2d game",
                "a 3d game",
                "3d game",
                "web game",
                "game"
            )

        removable.forEach {

            cleaned =
                cleaned.replace(
                    it,
                    " "
                )
        }

        cleaned =
            cleaned
                .replace(
                    Regex("[^a-z0-9 ]"),
                    " "
                )
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        val words =
            cleaned
                .split(" ")
                .filter {
                    it.isNotBlank()
                }
                .take(5)

        if (words.isNotEmpty()) {

            return sanitizeProjectName(
                words.joinToString("_")
            )
        }

        return when (type) {

            JarvisProjectType.WEBSITE ->
                "website"

            JarvisProjectType.GAME_2D ->
                "2d_game"

            JarvisProjectType.GAME_3D ->
                "3d_game"

            JarvisProjectType.UNKNOWN ->
                "project"
        }
    }

    private fun sanitizeProjectName(
        name: String
    ): String {

        val cleaned =
            name
                .lowercase(
                    Locale.getDefault()
                )
                .replace(
                    Regex("[^a-z0-9_-]"),
                    "_"
                )
                .replace(
                    Regex("_+"),
                    "_"
                )
                .trim('_')
                .take(50)

        return cleaned.ifBlank {
            "jarvis_project"
        }
    }

    private fun displayName(
        name: String
    ): String {

        return name
            .replace("_", " ")
            .split(" ")
            .filter {
                it.isNotBlank()
            }
            .joinToString(" ") {
                it.replaceFirstChar { character ->
                    character.uppercase()
                }
            }
            .ifBlank {
                "Jarvis Game"
            }
    }

    private fun escapeHtml(
        value: String
    ): String {

        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
