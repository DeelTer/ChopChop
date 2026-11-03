package ru.deelter.chopchop.core.net;

import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.profile.PartLimits;
import ru.deelter.chopchop.core.profile.Profiles;
import ru.deelter.chopchop.core.wound.PartState;
import ru.deelter.chopchop.core.wound.WoundState;

import java.io.*;
import java.util.BitSet;

/**
 * What a server backend (the Fabric mod or the Paper plugin) and the client mod say to each other. Compiled into
 * both, so they can't disagree about it.
 * <p>
 * One plugin channel, every message starts with a byte that says what it is. The server is the only one that
 * decides wounds: the client only says it is there, and draws what it is sent.
 */
public final class Protocol {
	public static final String CHANNEL = "chopchop:sync";
	public static final int VERSION = 2;
	public static final int MAX_MESSAGE = 32 * 1024;

	/**
	 * to the server: the mod is installed. Payload: version
	 */
	public static final byte C_HELLO = 1;
	/**
	 * to the server, right before an attack: where on the target the client saw the crosshair, on the pose it drew
	 * (animated, a swinging arm where it was). Only a hint: the server checks it and uses it only if it is plausible.
	 * Payload: entity, part, voxel x y z, the way in (3 floats), the cut (3 floats, all 0: none)
	 */
	public static final byte C_AIM = 2;
	public static final int AIM_LENGTH = 1 + 4 + 1 + 3 + 12 + 12;

	/**
	 * to the client: the server speaks the protocol. Payload: version
	 */
	public static final byte S_HELLO = 1;
	/**
	 * every wound of one entity, replacing what the client had
	 */
	public static final byte S_STATE = 2;
	/**
	 * something just happened to an entity: effects only, the state follows in its own message
	 */
	public static final byte S_EVENT = 3;
	/**
	 * an entity is whole again
	 */
	public static final byte S_CLEAR = 4;
	/**
	 * an entity died and stays as a body: how it falls, the same for every client. Payload: entity, direction of
	 * the fall in the world (x, z), its strength, a seed for the rest, ticks it lies there
	 */
	public static final byte S_CORPSE = 5;

	public static final byte EVENT_SPLASH = 0;
	public static final byte EVENT_WOUND = 1;
	public static final byte EVENT_SEVER = 2;
	/**
	 * a part torn off by a blast: it flies away hard
	 */
	public static final byte EVENT_BLAST = 3;

	private Protocol() {
	}

	/**
	 * what the client needs of the rules of the server, in {@link #hello(int)}: bodies without legs crawl
	 */
	public static final int FLAG_CRAWL = 1;

	public static byte[] hello() {
		return write(out -> {
			out.writeByte(C_HELLO);
			out.writeShort(VERSION);
		});
	}

	public static byte[] hello(int flags) {
		return write(out -> {
			out.writeByte(S_HELLO);
			out.writeShort(VERSION);
			out.writeInt(flags);
		});
	}

	public static int flags(ru.deelter.chopchop.core.rules.WoundRules rules) {
		return rules.crawls() ? FLAG_CRAWL : 0;
	}

	public static byte[] state(int entityId, WoundState state) {
		return write(out -> {
			out.writeByte(S_STATE);
			out.writeInt(entityId);
			out.writeUTF(state.getProfile().getId());
			out.writeInt(state.getRevision());
			int count = 0;
			for (int i = 0; i < state.partCount(); i++) if (!state.part(i).pristine()) count++;
			out.writeByte(count);
			for (int i = 0; i < state.partCount(); i++) {
				PartState part = state.part(i);
				if (part.pristine()) continue;
				out.writeByte(i);
				out.writeShort(part.severed() ? part.getSeveredAt() : -1);
				byte[] bits = part.carvedBits().toByteArray();
				out.writeShort(bits.length);
				out.write(bits);
			}
		});
	}

	/**
	 * @param model    point of the hit in model space, pixels
	 * @param attacker network id of who dealt the hit, -1 for none: that client may show a kill cam
	 */
	public static byte[] event(int entityId, byte kind, int part, float[] model, int severedAt, int attacker) {
		return write(out -> {
			out.writeByte(S_EVENT);
			out.writeInt(entityId);
			out.writeByte(kind);
			out.writeByte(part);
			out.writeFloat(model[0]);
			out.writeFloat(model[1]);
			out.writeFloat(model[2]);
			out.writeShort(severedAt);
			out.writeInt(attacker);
		});
	}

	/**
	 * @param dx       direction the body falls, in the world: where the last blow pushed it
	 * @param strength how hard, about 0 to 1
	 * @param ticks    how long it lies before it is gone
	 */
	public static byte[] corpse(int entityId, float dx, float dz, float strength, int seed, int ticks) {
		return write(out -> {
			out.writeByte(S_CORPSE);
			out.writeInt(entityId);
			out.writeFloat(dx);
			out.writeFloat(dz);
			out.writeFloat(strength);
			out.writeInt(seed);
			out.writeInt(ticks);
		});
	}

	public static byte[] clear(int entityId) {
		return write(out -> {
			out.writeByte(S_CLEAR);
			out.writeInt(entityId);
		});
	}

	/**
	 * Reads a message from the server. Anything malformed throws {@link IOException}, never a half read state.
	 */
	public static Message readClientbound(byte[] data, Profiles profiles) throws IOException {
		if (data.length > MAX_MESSAGE) throw new IOException("message too big");
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		byte type = in.readByte();
		return switch (type) {
			case S_HELLO -> new Message.Hello(in.readShort(), in.available() >= 4 ? in.readInt() : 0);
			case S_STATE -> {
				int entity = in.readInt();
				String profileId = in.readUTF();
				BodyProfile profile = profiles.byId(profileId);
				if (profile == null) throw new IOException("unknown profile " + profileId);
				WoundState state = new WoundState(profile);
				state.setRevision(in.readInt());
				int count = in.readUnsignedByte();
				if (count > PartLimits.MAX_PARTS) throw new IOException("too many parts");
				for (int i = 0; i < count; i++) {
					int index = in.readUnsignedByte();
					if (index >= state.partCount()) throw new IOException("part out of range");
					int severedAt = in.readShort();
					int length = in.readUnsignedShort();
					if (length > PartLimits.MAX_VOXELS / 8 + 1) throw new IOException("part too big");
					byte[] bits = new byte[length];
					in.readFully(bits);
					state.part(index).load(BitSet.valueOf(bits), severedAt);
				}
				yield new Message.State(entity, state);
			}
			case S_EVENT -> new Message.Event(in.readInt(), in.readByte(), in.readUnsignedByte(),
					new float[]{in.readFloat(), in.readFloat(), in.readFloat()}, in.readShort(), in.available() >= 4 ? in.readInt() : -1);
			case S_CLEAR -> new Message.Clear(in.readInt());
			case S_CORPSE -> {
				int entity = in.readInt();
				float dx = in.readFloat(), dz = in.readFloat(), strength = in.readFloat();
				if (!Float.isFinite(dx) || !Float.isFinite(dz) || !Float.isFinite(strength)) throw new IOException("bad corpse");
				yield new Message.Corpse(entity, dx, dz, Math.clamp(strength, 0, 2), in.readInt(), Math.clamp(in.readInt(), 0, 20 * 600));
			}
			default -> throw new IOException("unknown message " + type);
		};
	}

	public static byte[] aim(int entityId, int part, int x, int y, int z, float[] inward, float[] slash) {
		return write(out -> {
			out.writeByte(C_AIM);
			out.writeInt(entityId);
			out.writeByte(part);
			out.writeByte(x);
			out.writeByte(y);
			out.writeByte(z);
			for (int i = 0; i < 3; i++) out.writeFloat(inward == null ? 0 : inward[i]);
			for (int i = 0; i < 3; i++) out.writeFloat(slash == null ? 0 : slash[i]);
		});
	}

	/**
	 * Where a client says it hit.
	 *
	 * @param inward null if not known
	 * @param slash  null for none
	 */
	public record Aim(int entityId, int part, int x, int y, int z, float[] inward, float[] slash) {
	}

	/**
	 * @return the aim of a client, or null if the message is not one or is broken
	 */
	public static Aim readAim(byte[] data) {
		if (data == null || data.length != AIM_LENGTH || data[0] != C_AIM) return null;
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 1, data.length - 1));
			int entity = in.readInt();
			int part = in.readUnsignedByte(), x = in.readUnsignedByte(), y = in.readUnsignedByte(), z = in.readUnsignedByte();
			float[] inward = {in.readFloat(), in.readFloat(), in.readFloat()};
			float[] slash = {in.readFloat(), in.readFloat(), in.readFloat()};
			return new Aim(entity, part, x, y, z, unit(inward), unit(slash));
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * A direction made unit length, null if it is none or not a number.
	 */
	private static float[] unit(float[] v) {
		double l = 0;
		for (float f : v) {
			if (!Float.isFinite(f)) return null;
			l += f * f;
		}
		l = Math.sqrt(l);
		if (l < 1e-4) return null;
		return new float[]{(float) (v[0] / l), (float) (v[1] / l), (float) (v[2] / l)};
	}

	/**
	 * @return the protocol version of a client hello, or -1 if it is not one
	 */
	public static int readServerbound(byte[] data) {
		if (data == null || data.length != 3 || data[0] != C_HELLO) return -1;
		return ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
	}

	private static byte[] write(Writer writer) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			writer.write(out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bytes.toByteArray();
	}

	private interface Writer {
		void write(DataOutputStream out) throws IOException;
	}

	public sealed interface Message {
		record Hello(int version, int flags) implements Message {
		}

		record State(int entityId, WoundState state) implements Message {
		}

		record Event(int entityId, byte kind, int part, float[] model, int severedAt, int attacker) implements Message {
		}

		record Clear(int entityId) implements Message {
		}

		record Corpse(int entityId, float dx, float dz, float strength, int seed, int ticks) implements Message {
		}
	}
}
