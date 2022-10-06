/*
 * Copyright 2021 ICON Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package foundation.icon.btp.bmc;

import foundation.icon.btp.lib.BTPAddress;
import foundation.icon.btp.lib.BTPException;
import foundation.icon.btp.mock.MockRelayMessage;
import foundation.icon.btp.test.AssertBTPException;
import foundation.icon.btp.test.MockBMVIntegrationTest;
import foundation.icon.btp.test.MockBSHIntegrationTest;
import foundation.icon.jsonrpc.Address;
import foundation.icon.jsonrpc.model.TransactionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class MessageTest implements BMCIntegrationTest {
    static BTPAddress link = Faker.btpLink();
    static BTPAddress reachable = Faker.btpLink();
    static String svc = MockBSHIntegrationTest.SERVICE;
    static Address relay = Address.of(bmc._wallet());
    static BigInteger[] emptyFeeValues = new BigInteger[]{};
    //for intermediate path test
    static BTPAddress secondLink = Faker.btpLink();

    static byte[][] toBytesArray(List<BTPMessage> btpMessages) {
        int len = btpMessages.size();
        byte[][] bytesArray = new byte[len][];
        for (int i = 0; i < len; i++) {
            bytesArray[i] = btpMessages.get(i).toBytes();
        }
        return bytesArray;
    }

    static void ensureReachable(BTPAddress link, BTPAddress[] reachable) {
        InitMessage initMessage = new InitMessage();
        initMessage.setLinks(reachable);
        BMCMessage bmcMessage = new BMCMessage();
        bmcMessage.setType(BTPMessageCenter.Internal.Init.name());
        bmcMessage.setPayload(initMessage.toBytes());
        BTPMessage msg = new BTPMessage();
        msg.setSrc(link);
        msg.setDst(btpAddress);
        msg.setSvc(BTPMessageCenter.INTERNAL_SERVICE);
        msg.setSn(BigInteger.ZERO);
        msg.setPayload(bmcMessage.toBytes());
        msg.setFeeInfo(null);
        bmc.handleRelayMessage(
                msg.getSrc().toString(),
                mockRelayMessage(msg).toBase64String());
    }

    @BeforeAll
    static void beforeAll() {
        System.out.println("MessageTest:beforeAll start");
        bmc.setDumpJson(true);
        BMVManagementTest.addVerifier(link.net(), MockBMVIntegrationTest.mockBMV._address());
        LinkManagementTest.addLink(link.toString());
        BMRManagementTest.addRelay(link.toString(), relay);
        ensureReachable(link, new BTPAddress[]{reachable});

        BMVManagementTest.addVerifier(secondLink.net(), MockBMVIntegrationTest.mockBMV._address());
        LinkManagementTest.addLink(secondLink.toString());
        BMRManagementTest.addRelay(secondLink.toString(), relay);

        BSHManagementTest.clearService(svc);
        BSHManagementTest.addService(svc, MockBSHIntegrationTest.mockBSH._address());

        System.out.println("MessageTest:beforeAll end");
    }

    @AfterAll
    static void afterAll() {
        System.out.println("MessageTest:afterAll start");
        BSHManagementTest.clearService(svc);

        BMRManagementTest.clearRelay(link.toString(), relay);
        LinkManagementTest.clearLink(link.toString());
        BMVManagementTest.clearVerifier(link.net());

        BMRManagementTest.clearRelay(secondLink.toString(), relay);
        LinkManagementTest.clearLink(secondLink.toString());
        BMVManagementTest.clearVerifier(secondLink.net());
        System.out.println("MessageTest:afterAll end");
    }

    static void assertEqualsFeeInfo(FeeInfo o1, FeeInfo o2) {
        if (o1 == null) {
            assertNull(o2);
        } else {
            assertEquals(o1.getNetwork(), o2.getNetwork());
            assertArrayEquals(o1.getValues(), o2.getValues());
        }
    }

    static void assertEqualsBTPMessage(BTPMessage o1, BTPMessage o2) {
        assertEquals(o1.getSrc(), o2.getSrc());
        assertEquals(o1.getDst(), o2.getDst());
        assertEquals(o1.getSvc(), o2.getSvc());
        assertEquals(o1.getSn(), o2.getSn());
        assertArrayEquals(o1.getPayload(), o2.getPayload());
        assertEqualsFeeInfo(o1.getFeeInfo(), o2.getFeeInfo());
    }

    @ParameterizedTest
    @MethodSource("sendMessageShouldSuccessArguments")
    void sendMessageShouldSuccess(
            String display,
            BTPAddress dst, BTPAddress next, BigInteger sn) {
        System.out.println(display);
        byte[] payload = Faker.btpLink().toBytes();
        FeeInfo feeInfo = new FeeInfo(btpAddress.net(), emptyFeeValues);

        BigInteger txSeq = BMCIntegrationTest.getStatus(bmc, next.toString())
                .getTx_seq();
        Consumer<TransactionResult> checker = BMCIntegrationTest.messageEvent((el) -> {
            assertEquals(next.toString(), el.getNext());
            assertEquals(txSeq.add(BigInteger.ONE), el.getSeq());
            BTPMessage btpMessage = el.getMsg();
            assertEquals(btpAddress, btpMessage.getSrc());
            assertEquals(dst, btpMessage.getDst());
            assertEquals(svc, btpMessage.getSvc());
            assertEquals(sn, btpMessage.getSn());
            assertArrayEquals(payload, btpMessage.getPayload());
            assertEqualsFeeInfo(feeInfo, btpMessage.getFeeInfo());
        });
        MockBSHIntegrationTest.mockBSH.sendMessage(
                checker,
                bmc._address(),
                dst.net(), svc, sn, payload);
    }

    static Stream<Arguments> sendMessageShouldSuccessArguments() {
        return Stream.of(
                Arguments.of(
                        "unidirectionalSendToLink",
                        link, link,
                        BigInteger.ZERO),
                Arguments.of(
                        "bidirectionalSendToLink",
                        link, link,
                        BigInteger.ONE),
                Arguments.of(
                        "unidirectionalSendToReachable",
                        reachable, link,
                        BigInteger.ZERO),
                Arguments.of(
                        "bidirectionalSendToReachable",
                        reachable, link,
                        BigInteger.ONE)
        );
    }

    @SuppressWarnings("ThrowableNotThrown")
    @ParameterizedTest
    @MethodSource("sendMessageShouldRevertArguments")
    void sendMessageShouldRevert(
            String display,
            BTPException exception,
            String dstNet, String svc, BigInteger sn) {
        System.out.println(display);
        AssertBTPException.assertBTPException(exception, () ->
                MockBSHIntegrationTest.mockBSH.sendMessage(
                        bmc._address(),
                        dstNet, svc, sn, Faker.btpLink().toBytes()));
    }

    static Stream<Arguments> sendMessageShouldRevertArguments() {
        return Stream.of(
                Arguments.of(
                        "sendMessageShouldRevertNotExistsBSH",
                        BMCException.notExistsBSH(),
                        link.net(),
                        Faker.btpService(),
                        BigInteger.ZERO),
                Arguments.of(
                        "sendMessageShouldRevertUnreachable",
                        BMCException.unreachable(),
                        Faker.btpNetwork(),
                        svc,
                        BigInteger.ZERO),
                Arguments.of(
                        "replySendMessageShouldRevert",
                        BMCException.unknown("not exists response"),
                        link.net(),
                        svc,
                        BigInteger.valueOf(Long.MAX_VALUE).negate())
        );
    }

    @Test
    void sendMessageShouldRevertUnauthorized() {
        AssertBMCException.assertUnauthorized(() -> bmc.sendMessage(
                (txr) -> {},
                link.net(), svc, BigInteger.ZERO, Faker.btpLink().toBytes()));
    }


    @ParameterizedTest
    @MethodSource("handleRelayMessageShouldSuccessArguments")
    void handleRelayMessageShouldSuccess(
            String display,
            BTPAddress src, BTPAddress dst, BTPAddress prev, BTPAddress next,
            String svc, BigInteger sn, BTPException expectBTPError) {
        System.out.println(display);
        BTPMessage msg = new BTPMessage();
        msg.setSrc(src);
        msg.setDst(dst);
        msg.setSvc(svc);
        msg.setSn(sn);
        msg.setPayload(Faker.btpLink().toBytes());
        msg.setFeeInfo(new FeeInfo(src.net(), emptyFeeValues));

        System.out.println("handleRelayMessageShouldIncreaseRxSeq");
        Consumer<TransactionResult> checker = rxSeqChecker(prev);
        if (expectBTPError != null) {
            System.out.println("handleRelayMessageShouldReplyBTPError");
            checker = checker.andThen(replyBTPErrorChecker(prev, msg, expectBTPError));
        } else {
            if (!dst.equals(btpAddress)) {
                if (next != null) {
                    System.out.println("handleRelayMessageShouldSendToNext");
                    checker = checker.andThen(routeChecker(next, msg));
                } else {
                    System.out.println("handleRelayMessageShouldDrop");
//                    checker = checker.andThen(dropChecker(prev, msg));
                }
            } else {
                if (svc.equals(MessageTest.svc)) {
                    System.out.println("handleRelayMessageShouldCallHandleBTPMessage");
                    checker = checker.andThen(handleBTPMessageChecker(msg));
                    if (sn.compareTo(BigInteger.ZERO) > 0) {
                        //handleRelayMessageShouldStoreFeeInfo
                        System.out.println("handleRelayMessageShouldStoreResponse");
                        checker = checker.andThen(storeFeeInfoChecker(prev, msg));
                    }
                } else {
                    System.out.println("handleRelayMessageShouldDrop");
//                    checker = checker.andThen(dropChecker(prev, msg));
                }
            }
        }
        bmc.handleRelayMessage(
                checker,
                prev.toString(),
                mockRelayMessage(msg).toBase64String());
    }

    static Consumer<TransactionResult> rxSeqChecker(
            final BTPAddress prev) {
        BigInteger rxSeq = BMCIntegrationTest.getStatus(bmc, prev.toString())
                .getRx_seq();
        return (txr) -> {
            assertEquals(rxSeq.add(BigInteger.ONE),
                    BMCIntegrationTest.getStatus(bmc, prev.toString()).getRx_seq());
        };
    }

    static Consumer<TransactionResult> handleBTPMessageChecker(
            final BTPMessage msg) {
        return MockBSHIntegrationTest.handleBTPMessageEvent((el) -> {
            assertEquals(msg.getSrc().net(), el.getFrom());
            assertEquals(msg.getSvc(), el.getSvc());
            assertEquals(msg.getSn(), el.getSn());
            assertArrayEquals(msg.getPayload(), el.getMsg());
        });
    }

    static Consumer<TransactionResult> sendMessageChecker(
            final BTPAddress next, final BTPMessage msg) {
        BigInteger txSeq = BMCIntegrationTest.getStatus(bmc, next.toString())
                .getTx_seq();
        return BMCIntegrationTest.messageEvent((el) -> {
            assertEquals(next.toString(), el.getNext());
            assertEquals(txSeq.add(BigInteger.ONE), el.getSeq());
            assertEqualsBTPMessage(msg, el.getMsg());
        });
    }

    static Consumer<TransactionResult> routeChecker(
            final BTPAddress next, final BTPMessage msg) {
        BTPMessage routeMsg = new BTPMessage();
        routeMsg.setSrc(msg.getSrc());
        routeMsg.setDst(msg.getDst());
        routeMsg.setSvc(msg.getSvc());
        routeMsg.setSn(msg.getSn());
        routeMsg.setPayload(msg.getPayload());
        routeMsg.setFeeInfo(msg.getFeeInfo());
        return sendMessageChecker(next, routeMsg);
    }

    static ErrorMessage toErrorMessage(BTPException exception) {
        ErrorMessage errMsg = new ErrorMessage();
        errMsg.setCode(exception.getCode());
        errMsg.setMsg(exception.getMessage());
        return errMsg;
    }

    Consumer<TransactionResult> replyBTPErrorChecker(
            final BTPAddress prev, final BTPMessage msg, final BTPException exception) {
        BTPMessage replyMsg = new BTPMessage();
        replyMsg.setSrc(btpAddress);
        replyMsg.setDst(msg.getSrc());
        replyMsg.setSvc(msg.getSvc());
        replyMsg.setSn(msg.getSn().negate());
        replyMsg.setPayload(toErrorMessage(exception).toBytes());
        replyMsg.setFeeInfo(msg.getFeeInfo());
        return sendMessageChecker(prev, replyMsg);
    }

    Consumer<TransactionResult> storeFeeInfoChecker(
            final BTPAddress prev, final BTPMessage msg) {
        return (txr) -> {
            BTPMessage resp = new BTPMessage();
            resp.setSrc(msg.getDst());
            resp.setDst(msg.getSrc());
            resp.setSvc(msg.getSvc());
            resp.setSn(BigInteger.ZERO);
            resp.setPayload(msg.getPayload());
            resp.setFeeInfo(new FeeInfo(
                    msg.getFeeInfo().getNetwork(),
                    emptyFeeValues));
            Consumer<TransactionResult> checker = sendMessageChecker(
                    prev, resp);
            MockBSHIntegrationTest.mockBSH.sendMessage(
                    checker,
                    bmc._address(),
                    resp.getDst().net(), resp.getSvc(), msg.getSn().negate(), resp.getPayload());
        };
    }

    static Consumer<TransactionResult> dropChecker(
            final BTPAddress prev, final BTPMessage msg) {
        BigInteger rxSeq = BMCIntegrationTest.getStatus(bmc, prev.toString())
                .getRx_seq();
        return BMCIntegrationTest.messageDroppedEvent((el) -> {
            assertEquals(prev.toString(), el.getLink());
            assertEquals(rxSeq.add(BigInteger.ONE), el.getSeq());
            assertEqualsBTPMessage(msg, el.getMsg());
        });
    }

    static Stream<Arguments> handleRelayMessageShouldSuccessArguments() {
        return Stream.of(
                Arguments.of(
                        "unidirectionalHandleRelayMessage",
                        link, btpAddress, link, null,
                        svc, BigInteger.ZERO,
                        null),
                Arguments.of(
                        "bidirectionalHandleRelayMessage",
                        link, btpAddress, link, null,
                        svc, BigInteger.ONE,
                        null),
                Arguments.of(
                        "unidirectionalHandleRelayMessageShouldNotReplyBTPError",
                        link, btpAddress, link, null,
                        Faker.btpService(), BigInteger.ZERO,
                        null),
                Arguments.of(
                        "bidirectionalHandleRelayMessageShouldReplyBTPError",
                        link, btpAddress, link, null,
                        Faker.btpService(), BigInteger.ONE,
                        BMCException.notExistsBSH()),
                Arguments.of(
                        "handleRelayMessageInIntermediate",
                        secondLink, link, secondLink, link,
                        svc, BigInteger.ZERO,
                        null),
                Arguments.of(
                        "handleRelayMessageShouldNotReplyBTPErrorInIntermediate",
                        secondLink, Faker.btpLink(), secondLink, null,
                        svc, BigInteger.ZERO,
                        null),
                Arguments.of(
                        "handleRelayMessageShouldReplyBTPErrorInIntermediate",
                        secondLink, Faker.btpLink(), secondLink, null,
                        svc, BigInteger.ONE,
                        BMCException.unreachable())
        );
    }

    @Test
    void handleRelayMessageShouldCallHandleBTPError() {
        ErrorMessage errorMsg = toErrorMessage(BMCException.unknown("error"));
        BTPMessage msg = new BTPMessage();
        msg.setSrc(link);
        msg.setDst(btpAddress);
        msg.setSvc(svc);
        msg.setSn(BigInteger.ONE.negate());
        msg.setPayload(errorMsg.toBytes());
        msg.setFeeInfo(new FeeInfo(
                btpAddress.net(), emptyFeeValues));
        Consumer<TransactionResult> checker = MockBSHIntegrationTest.handleBTPErrorEvent(
                (el) -> {
                    assertEquals(msg.getSrc().toString(), el.getSrc());
                    assertEquals(msg.getSvc(), el.getSvc());
                    assertEquals(msg.getSn().negate(), el.getSn());
                    assertEquals(errorMsg.getCode(), el.getCode());
                    assertEquals(errorMsg.getMsg(), el.getMsg());
                }
        );
        bmc.handleRelayMessage(
                checker,
                link.toString(),
                mockRelayMessage(msg).toBase64String());
    }

    static BTPMessage btpMessageForSuccess(BTPAddress src) {
        BTPMessage msg = new BTPMessage();
        msg.setSrc(src);
        msg.setDst(btpAddress);
        msg.setSvc(svc);
        msg.setSn(BigInteger.ONE);
        msg.setPayload(Faker.btpLink().toBytes());
        msg.setFeeInfo(new FeeInfo(
                btpAddress.net(), emptyFeeValues));
        return msg;
    }

    static MockRelayMessage mockRelayMessage(BTPMessage... msgs) {
        MockRelayMessage relayMessage = new MockRelayMessage();
        relayMessage.setBtpMessages(toBytesArray(List.of(msgs)));
        return relayMessage;
    }

    @Test
    void handleRelayMessageShouldRevertNotExistsLink() {
        AssertBMCException.assertNotExistsLink(() ->
                bmc.handleRelayMessage(Faker.btpLink().toString(),
                        mockRelayMessage(btpMessageForSuccess(link)).toBase64String()));
    }

    @Test
    void handleRelayMessageShouldRevertUnauthorized() {
        AssertBMCException.assertUnauthorized(() ->
                bmcWithTester.handleRelayMessage(link.toString(),
                        mockRelayMessage(btpMessageForSuccess(link)).toBase64String()));
    }

    static String[] fragments(byte[] bytes, int count) {
        int len = bytes.length;
        if (len < count || count < 1) {
            throw new IllegalArgumentException();
        }
        int fLen = len / count;
        if (len % count != 0) {
            fLen++;
        }
        int begin = 0;
        String[] arr = new String[count];
        for (int i = 0; i < count; i++) {
            int end = begin + fLen;
            byte[] fragment = null;
            if (end < len) {
                fragment = Arrays.copyOfRange(bytes, begin, end);
            } else {
                fragment = Arrays.copyOfRange(bytes, begin, len);
            }
            arr[i] = Base64.getUrlEncoder().encodeToString(fragment);
            begin = end;
        }
        return arr;
    }

    @Test
    void handleFragment() {
        //BMC.handleFragment -> BMC.handleRelayMessage -> BSHMock.HandleBTPMessage
        BTPMessage msg = btpMessageForSuccess(link);
        MockRelayMessage relayMessage = mockRelayMessage(msg);
        byte[] bytes = relayMessage.toBytes();
        int count = 3;
        int last = count - 1;
        String[] fragments = fragments(bytes, count);
        for (int i = 0; i < count; i++) {
            if (i == 0) {
                iconSpecific.handleFragment(link.toString(), fragments[i], -1 * last);
            } else if (i == last) {
                Consumer<TransactionResult> checker = handleBTPMessageChecker(msg);
                iconSpecific.handleFragment(
                        checker,
                        link.toString(), fragments[i], 0);
            } else {
                iconSpecific.handleFragment(link.toString(), fragments[i], last - i);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("dropMessageShouldSuccessArguments")
    void dropMessageShouldSuccess(
            String display,
            BigInteger sn) {
        System.out.println(display);

        BTPMessage assumeMsg = new BTPMessage();
        assumeMsg.setSrc(link);
        assumeMsg.setSvc(svc);
        assumeMsg.setDst(BTPAddress.parse(""));
        assumeMsg.setSn(sn);
        assumeMsg.setPayload(new byte[0]);
        assumeMsg.setFeeInfo(new FeeInfo(
                link.net(), emptyFeeValues));

        System.out.println("dropMessageShouldIncreaseRxSeqAndDrop");
        BigInteger rxSeq = BMCIntegrationTest.getStatus(bmc, link.toString())
                .getRx_seq();
        Consumer<TransactionResult> checker = rxSeqChecker(link)
                .andThen(dropChecker(link, assumeMsg));
        if (sn.compareTo(BigInteger.ZERO) > 0) {
            System.out.println("dropMessageShouldReplyBTPError");
            checker = checker.andThen(replyBTPErrorChecker(link, assumeMsg, BMCException.drop()));
        }
        iconSpecific.dropMessage(checker,
                assumeMsg.getSrc().toString(),
                rxSeq.add(BigInteger.ONE),
                assumeMsg.getSvc(),
                sn,
                assumeMsg.getFeeInfo().getNetwork(),
                assumeMsg.getFeeInfo().getValues());
    }

    static Stream<Arguments> dropMessageShouldSuccessArguments() {
        return Stream.of(
                Arguments.of(
                        "unidirectionalDropMessage",
                        BigInteger.ZERO),
                Arguments.of(
                        "bidirectionalDropMessage",
                        BigInteger.ONE)
        );
    }

    @SuppressWarnings("ThrowableNotThrown")
    @ParameterizedTest
    @MethodSource("dropMessageShouldRevertArguments")
    void dropMessageShouldRevert(
            String display,
            BTPException exception,
            BTPAddress src, String svc, BigInteger sn) {
        System.out.println(display);
        BigInteger rxSeq = BMCIntegrationTest.getStatus(bmc, link.toString())
                .getRx_seq();
        AssertBTPException.assertBTPException(exception, () ->
                iconSpecific.dropMessage(
                        src.toString(), rxSeq.add(BigInteger.ONE), svc, sn, "", new BigInteger[]{}));
    }

    static Stream<Arguments> dropMessageShouldRevertArguments() {
        return Stream.of(
                Arguments.of(
                        "dropMessageShouldRevertUnreachable",
                        BMCException.unreachable(),
                        Faker.btpLink(),
                        svc,
                        BigInteger.ZERO),
                Arguments.of(
                        "dropMessageShouldRevertNotExistsBSH",
                        BMCException.notExistsBSH(),
                        link,
                        Faker.btpService(),
                        BigInteger.ZERO),
                Arguments.of(
                        "dropMessageShouldRevertInvalidSn",
                        BMCException.invalidSn(),
                        link,
                        svc,
                        BigInteger.ONE.negate())
        );
    }

    @Test
    void dropMessageShouldRevertInvalidSeq() {
        BigInteger rxSeq = BMCIntegrationTest.getStatus(bmc, link.toString())
                .getRx_seq();
        String src = link.toString();
        BigInteger sn = BigInteger.ZERO;
        FeeInfo feeInfo = new FeeInfo(link.net(), emptyFeeValues);
        AssertBMCException.assertUnknown(() -> iconSpecific.dropMessage(
                src, rxSeq, svc, sn, feeInfo.getNetwork(), feeInfo.getValues()));
        AssertBMCException.assertUnknown(() -> iconSpecific.dropMessage(
                src, rxSeq.add(BigInteger.TWO), svc, sn, feeInfo.getNetwork(), feeInfo.getValues()));
    }
}
