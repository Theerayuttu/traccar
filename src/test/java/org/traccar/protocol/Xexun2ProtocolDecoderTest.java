package org.traccar.protocol;

import org.junit.Test;
import org.traccar.ProtocolTest;

public class Xexun2ProtocolDecoderTest extends ProtocolTest {

    @Test
    public void testDecode() throws Exception {

        var decoder = inject(new Xexun2ProtocolDecoder(null));

        verifyPositions(decoder, false, binary(
                "faaf00140a5a8618810536243350005ed8e101005b64622880401b001482060864cc2296f840daa22aa884f008c87483c291efddc4f09fc2f49db3c058ef68005a9abe1ae8299d6449bac4e984e0c1d6baa8469d265ff2b60100cc00080000fb2e0013572a3600000002000000000000faaf"));

        verifyPositions(decoder, false, binary(
                "FAAF00140004863921033475388000AFB7D203003800380038F9608A7B801E0060820205788A205DF523D97844FDB90443D37844FDB90465CFB4FBF946B0E8CEF639095803F8CC00000002350000004000FA608A7BA81E0060820205788A205DF523D97844FDB90443D2F639095803F8CFB4FBF946B0E8CE7844FDB90465CD00000002350000004000FB608A7BD01E0060820205788A205DF523D97844FDB90443D2F639095803F8CFB4FBF946B0E8CE7844FDB90465CD00000002350000004000FAAF"));

        verifyPositions(decoder, false, binary(
                "faaf0014000286147503139003400032f2b001002f4260b0d6a0008019104a3378323130333135317c323130333132303100704020308715758089502023015648643670faaf"));

        verifyPositions(decoder, false, binary(
                "FAAF0014000486188105421927500035E6D2010032FC60EC264D00002003000000020205444E6DD72699D674427F7712CBC3BCF2AFD910BAC1C6FBE474CFC7A9B4FBE474CFC7A6FAAF"));

        verifyPositions(decoder, binary(
                "FAAF00140CF18626490454584530002BF2DD0200130013D360EFD7F514006402010D46322C4A450BA026D460EFD7FA14006402010D46322C4A450BA026FAAF"));

        verifyPositions(decoder, binary(
                "FAAF0014000C8622050512345670002DF3A001002A0062D9047400005E0280001E47001B400D4BA732DF505E40B4153AAF78FEF00109000000000042B36666FAAF"));

    }

}
