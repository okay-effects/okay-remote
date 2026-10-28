package okay.remote

class EnvelopeSuite extends munit.FunSuite:
  test("a frame from the other side, in time, new: taken; again: not") {
    val seen = Envelope.Seen()
    val f = Envelope.wrap("phone", 1000000, "abcdef0123", "hello")
    assertEquals(Envelope.open(f, "phone", 1000500, seen).map(_._1), Right("hello"))
    assertEquals(Envelope.open(f, "phone", 1000600, seen), Left("seen before"))
  }
  test("reflected, stale, from the future, without a nonce: not taken") {
    val seen = Envelope.Seen()
    assertEquals(Envelope.open(Envelope.wrap("daemon", 0, "n0000001", "x"), "phone", 0, seen),
      Left("from the wrong side"))
    assertEquals(Envelope.open(Envelope.wrap("phone", 0, "n0000002", "x"), "phone", Envelope.windowMs + 1, seen),
      Left("out of time"))
    assertEquals(Envelope.open(Envelope.wrap("phone", Envelope.windowMs + 1, "n0000003", "x"), "phone", 0, seen),
      Left("out of time"))
    assertEquals(Envelope.open(Envelope.wrap("phone", 0, "", "x"), "phone", 0, seen), Left("seen before"))
  }
