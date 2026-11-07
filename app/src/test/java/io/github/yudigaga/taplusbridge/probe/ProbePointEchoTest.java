package io.github.yudigaga.taplusbridge.probe;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ProbePointEchoTest {
    @Test public void injectedPointerEchoMustMatchTheSameDisplayDownAndPoint() throws Exception {
        Class<?> type=Class.forName("io.github.yudigaga.taplusbridge.probe.ProbePointEcho");
        Object echo=type.getDeclaredConstructor().newInstance();
        var expect=type.getDeclaredMethod("expect",int.class,long.class,int.class,float.class,float.class);
        var observe=type.getDeclaredMethod("observe",int.class,long.class,int.class,float.class,float.class);
        var valid=type.getDeclaredMethod("valid",int.class,long.class);
        expect.invoke(echo,0,1000L,0,100f,800f);
        assertEquals("unrelated",observe.invoke(echo,1,1000L,0,100f,800f));
        assertEquals("unrelated",observe.invoke(echo,0,2000L,0,100f,800f));
        assertEquals("confirmed",observe.invoke(echo,0,1000L,0,100.2f,800.2f));
        assertEquals(true,valid.invoke(echo,0,1000L));
        expect.invoke(echo,0,3000L,0,100f,800f);
        assertEquals("mismatch",observe.invoke(echo,0,3000L,0,400f,300f));
        assertEquals(false,valid.invoke(echo,0,3000L));
    }
}
