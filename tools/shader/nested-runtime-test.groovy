// Exercise the actual opt-in extraction logic using disposable, synthetic jar-in-jar fixtures.
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import groovy.json.JsonOutput

class GradleException extends RuntimeException { GradleException(String message) { super(message) } }
class FileProvider { File asFile; FileProvider get() { this } }
def root = java.nio.file.Files.createTempDirectory('screen-shader-nested-').toFile()
def jar = { File path, Map entries ->
    path.parentFile.mkdirs()
    new ZipOutputStream(new FileOutputStream(path)).withCloseable { out ->
        entries.each { name, content -> out.putNextEntry(new ZipEntry(name)); out.write(content instanceof byte[] ? content : content.toString().bytes); out.closeEntry() }
    }
    path
}
def child = { String version, String payload ->
    jar(new File(root,"child-${payload}.jar"), ['payload.txt':payload]).bytes
}
def parent = { String name, String version, String payload, String group='test', String artifact='shared-library' ->
    def entry = [identifier:[group:group,artifact:artifact],version:[artifactVersion:version],path:'nested/library.jar']
    jar(new File(root,name), ['META-INF/jarjar/metadata.json':JsonOutput.toJson([jars:[entry]]),'nested/library.jar':child(version,payload)])
}
def evaluate = { List parents, String target = 'neoforge-1.21.1' ->
    def binding = new Binding([
        project:[name:target], plugins:[hasPlugin:{String id -> false}], jars:parents,
        layout:[buildDirectory:[file:{String path -> new FileProvider(asFile:new File(root,path))}]]
    ])
    def source = new File('shader-runtime.gradle').text
    def start = source.indexOf('    def nestedMods')
    def end = source.indexOf("    if (project.name == 'fabric-1.20.1')")
    assert start >= 0 && end > start
    new GroovyShell(this.class.classLoader,binding).evaluate(source.substring(start,end)+'\nreturn [nestedMods,nestedLibraries]')
}
try {
    def a=parent('a.jar','1.0','one'), b=parent('b.jar','1.0','two'), c=parent('c.jar','2.0','three')
    // Official parents can package the same declared component version with different ZIP bytes.
    def result=evaluate([a,b])
    assert result[0].empty && result[1].size()==1
    assert new java.util.zip.ZipFile(result[1][0]).withCloseable { it.getInputStream(it.getEntry('payload.txt')).text } == 'one'
    boolean rejected=false
    try { evaluate([a,c]) } catch (GradleException expected) { rejected=expected.message.contains('Conflicting nested') }
    assert rejected : 'Different unreviewed versions must fail closed'
    for (def identity : [['io.github.llamalad7','mixinextras-forge'],['com.github.LlamaLad7','MixinExtras']]) {
        def oldMixin=parent('old-mixin.jar','0.3.5','old',identity[0],identity[1])
        def newMixin=parent('new-mixin.jar','0.4.1','new',identity[0],identity[1])
        for (def order : [[oldMixin,newMixin],[newMixin,oldMixin]]) {
            def chosen=evaluate(order)[1]
            assert chosen.size()==1
            assert new java.util.zip.ZipFile(chosen[0]).withCloseable { it.getInputStream(it.getEntry('payload.txt')).text } == 'new'
        }
    }
    def api=jar(new File(root,'api.jar'), ['fabric.mod.json':JsonOutput.toJson([id:'fabric-api-base',version:'1.0'])])
    def config=jar(new File(root,'config.jar'), ['fabric.mod.json':JsonOutput.toJson([id:'caffeineconfig',version:'1.0'])])
    def fabric=jar(new File(root,'fabric.jar'), [
        'fabric.mod.json':JsonOutput.toJson([id:'test-parent',version:'1.0',jars:[[file:'nested/api.jar'],[file:'nested/config.jar']]]),
        'nested/api.jar':api.bytes,'nested/config.jar':config.bytes])
    def fabricResult=evaluate([fabric],'fabric-1.20.1')
    assert fabricResult[0].size()==1 && fabricResult[1].empty : 'Keep nested mods, but do not duplicate Fabric API'
    println 'Shader nested-runtime regression passed'
} finally { root.deleteDir() }
