package net.ornithemc.condor.lvt;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.ornithemc.condor.representation.Classpath;

public class LocalVariableTableGenerator {

	private final InstructionMarker marker = new InstructionMarker();
	private final FrameBuilder frames = new FrameBuilder(this.marker);
	private final LocalVariableTweaker tweaker = new LocalVariableTweaker(this.marker, this.frames);
	private final LocalVariableBuilder builder = new LocalVariableBuilder(this.marker, this.frames);

	public void init(Classpath classpath, ClassNode cls, MethodNode method) {
		this.marker.init(classpath, cls, method);
		this.frames.init(classpath, cls, method);
		this.tweaker.init(classpath, cls, method);
		this.builder.init(classpath, cls, method);
	}

	public void run() {
		// must be done before computing frames
		this.marker.markTryCatchBlocks();
		// mark entries, exits, code jumps
		this.marker.markEntriesAndExits();
		// stack frame setup
		this.frames.computeInitialFrame();
		// expand stack frames
		this.frames.expandFrames();
		// compute stack frames
		this.frames.computeFrames();
		// finalize entries, exits, code jumps
		this.marker.processTryCatchBlocks();
		this.marker.processEntryPoints();
		// compute var liveness
		this.frames.computeLiveness();
		// clean up stack frames
		this.frames.processFrames();
		// tweak vars in stack frames
		this.tweaker.processLocalsOnInsn();
		this.tweaker.processLocalsOnStore();
		// build local variables
		this.builder.build();
	}
}
