package edu.zjnu.interview.ai.agent.multi;

import edu.zjnu.interview.ai.agent.AgentResult;
import edu.zjnu.interview.ai.agent.ToolRegistry;
import edu.zjnu.interview.ai.agent.lab.ScriptedChatModel;
import edu.zjnu.interview.ai.agent.memory.MemoryStore;
import edu.zjnu.interview.ai.agent.tools.CalculatorTool;
import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多 Agent 协作：主管分派、专家隔离、汇总、失败重派。
 */
class MultiAgentTest {

    private static ScriptedChatModel oneShot(String text) {
        return ScriptedChatModel.constant("test-model", ChatResponse.text(text));
    }

    @Test
    @DisplayName("主管按关键词把任务派给最合适的专家")
    void delegatesByKeyword() {
        SpecialistAgent researcher = SpecialistAgent.builder("researcher", "负责检索资料",
                        oneShot("检索结论"), new ToolRegistry())
                .keywords("资料", "费率")
                .build();
        SpecialistAgent calculator = SpecialistAgent.builder("calculator", "负责计算",
                        oneShot("计算结论"), new ToolRegistry())
                .keywords("计算")
                .build();

        SupervisorAgent supervisor = SupervisorAgent.builder("supervisor", oneShot("最终结论"))
                .specialist(researcher)
                .specialist(calculator)
                .planner(new KeywordPlanner())
                .maxRounds(2)
                .build();

        SupervisorAgent.SupervisorResult result = supervisor.run("请查一下费率资料");

        assertEquals(List.of("researcher"), result.delegatedSpecialists());
        assertEquals(1, result.rounds());
        assertEquals("最终结论", result.synthesis());
        assertTrue(result.success());
    }

    @Test
    @DisplayName("任务命中多个专家时并行委派")
    void delegatesToMultipleSpecialists() {
        SpecialistAgent researcher = SpecialistAgent.builder("researcher", "负责检索资料",
                        oneShot("检索结论"), new ToolRegistry())
                .keywords("资料")
                .build();
        SpecialistAgent calculator = SpecialistAgent.builder("calculator", "负责计算",
                        oneShot("计算结论"), new ToolRegistry())
                .keywords("计算")
                .build();

        SupervisorAgent supervisor = SupervisorAgent.builder("supervisor", oneShot("最终结论"))
                .specialist(researcher)
                .specialist(calculator)
                .planner(new KeywordPlanner())
                .maxRounds(1)
                .build();

        SupervisorAgent.SupervisorResult result = supervisor.run("请查资料并计算");

        assertEquals(List.of("researcher", "calculator"), result.delegatedSpecialists());
        assertEquals(2, result.delegations().size());
    }

    @Test
    @DisplayName("命中不到关键词时派给兜底专家")
    void fallsBackToGeneralist() {
        SpecialistAgent generalist = SpecialistAgent.builder("generalist", "什么都能干",
                        oneShot("通用结论"), new ToolRegistry())
                .generalist(true)
                .build();
        SpecialistAgent researcher = SpecialistAgent.builder("researcher", "负责检索资料",
                        oneShot("检索结论"), new ToolRegistry())
                .keywords("资料")
                .build();

        SupervisorAgent supervisor = SupervisorAgent.builder("supervisor", oneShot("最终结论"))
                .specialist(generalist)
                .specialist(researcher)
                .planner(new KeywordPlanner())
                .maxRounds(1)
                .build();

        SupervisorAgent.SupervisorResult result = supervisor.run("随便聊聊");

        assertEquals(List.of("generalist"), result.delegatedSpecialists());
    }

    @Test
    @DisplayName("专家的工具集互相隔离：检索专家看不到计算工具")
    void specialistsHaveIsolatedToolSets() {
        ToolRegistry researchTools = new ToolRegistry();
        ToolRegistry calcTools = new ToolRegistry().register(new CalculatorTool());

        SpecialistAgent researcher = SpecialistAgent.builder("researcher", "检索",
                        oneShot("结论"), researchTools).build();
        SpecialistAgent calculator = SpecialistAgent.builder("calculator", "计算",
                        oneShot("结论"), calcTools).build();

        assertEquals(0, researcher.tools().size());
        assertEquals(1, calculator.tools().size());
        assertFalse(researcher.tools().contains("calculator"));
        assertTrue(calculator.tools().contains("calculator"));
    }

    @Test
    @DisplayName("专家之间共享事实记忆，但会话上下文互相隔离")
    void specialistsShareFactsButNotContext() {
        MemoryStore memory = new MemoryStore();

        SpecialistAgent researcher = SpecialistAgent.builder("researcher", "检索",
                        oneShot("检索到：费率 1.5%"), new ToolRegistry())
                .memory(memory)
                .build();
        SpecialistAgent calculator = SpecialistAgent.builder("calculator", "计算",
                        oneShot("计算完成"), new ToolRegistry())
                .memory(memory)
                .build();

        researcher.run("查费率");
        calculator.run("计算 1+1");

        // 结论写进了共享记忆（USER 级作用域）
        assertTrue(memory.get("agent:researcher:lastConclusion", MemoryStore.Scope.USER).isPresent());
        assertTrue(memory.get("agent:calculator:lastConclusion", MemoryStore.Scope.USER).isPresent());
        // 但会话上下文互不可见
        assertFalse(researcher.state().messages().stream()
                .anyMatch(m -> m.content().contains("计算完成")));
        assertFalse(calculator.state().messages().stream()
                .anyMatch(m -> m.content().contains("费率 1.5%")));
    }

    @Test
    @DisplayName("专家失败时主管会重派（最多 maxRounds 轮）")
    void retriesFailedDelegation() {
        // 专家始终失败（模型一直调工具但工具永远失败）
        ScriptedChatModel broken = new ScriptedChatModel("broken", List.of(
                request -> ChatResponse.toolCall("c1", "exploding", "{}")));

        SpecialistAgent failing = SpecialistAgent.builder("researcher", "检索",
                        broken, new ToolRegistry().register(new edu.zjnu.interview.ai.core.Tool() {
                            @Override
                            public edu.zjnu.interview.ai.core.ToolSpec spec() {
                                return edu.zjnu.interview.ai.core.ToolSpec.builder("exploding", "总是失败")
                                        .sideEffect(edu.zjnu.interview.ai.core.ToolSpec.SideEffect.READ_ONLY)
                                        .build();
                            }

                            @Override
                            public edu.zjnu.interview.ai.core.ToolResult execute(ToolCall call,
                                                                                edu.zjnu.interview.ai.core.ToolContext ctx) {
                                throw new IllegalStateException("炸了");
                            }
                        }))
                .keywords("资料")
                .maxSteps(1)
                .build();

        SupervisorAgent supervisor = SupervisorAgent.builder("supervisor", oneShot("最终结论"))
                .specialist(failing)
                .planner(new KeywordPlanner())
                .maxRounds(2)
                .build();

        SupervisorAgent.SupervisorResult result = supervisor.run("请查资料");

        assertEquals(2, result.rounds(), "失败后应再委派一轮");
        assertEquals(2, result.delegations().size());
    }

    @Test
    @DisplayName("模型路由输出非法 JSON 时降级为关键词路由")
    void modelPlannerFallsBackOnGarbage() {
        ScriptedChatModel garbage = ScriptedChatModel.constant("garbage-model",
                ChatResponse.text("我不知道该派给谁，随便吧"));
        SpecialistAgent researcher = SpecialistAgent.builder("researcher", "检索资料",
                        oneShot("检索结论"), new ToolRegistry())
                .keywords("资料")
                .build();

        SupervisorAgent supervisor = SupervisorAgent.builder("supervisor", oneShot("最终结论"))
                .specialist(researcher)
                .planner(new ModelPlanner(garbage, new KeywordPlanner()))
                .maxRounds(1)
                .build();

        SupervisorAgent.SupervisorResult result = supervisor.run("请查资料");

        assertEquals(List.of("researcher"), result.delegatedSpecialists());
    }

    @Test
    @DisplayName("模型路由输出合法 JSON 时按模型决定分派")
    void modelPlannerUsesModelDecision() {
        ScriptedChatModel plannerModel = ScriptedChatModel.constant("planner-model",
                ChatResponse.text("{\"assignments\":[{\"specialist\":\"researcher\",\"task\":\"查费率\"}]}"));
        SpecialistAgent researcher = SpecialistAgent.builder("researcher", "检索资料",
                        oneShot("检索结论"), new ToolRegistry())
                .keywords("资料")
                .build();
        SpecialistAgent calculator = SpecialistAgent.builder("calculator", "计算",
                        oneShot("计算结论"), new ToolRegistry())
                .keywords("计算")
                .build();

        SupervisorAgent supervisor = SupervisorAgent.builder("supervisor", oneShot("最终结论"))
                .specialist(researcher)
                .specialist(calculator)
                .planner(new ModelPlanner(plannerModel, new KeywordPlanner()))
                .maxRounds(1)
                .build();

        SupervisorAgent.SupervisorResult result = supervisor.run("随便什么任务");

        assertEquals(List.of("researcher"), result.delegatedSpecialists());
        assertEquals("查费率", result.delegations().get(0).task());
    }

    @Test
    @DisplayName("模型编造不存在的专家时该条被丢弃，不影响其它委派")
    void modelPlannerDropsUnknownSpecialists() {
        ScriptedChatModel plannerModel = ScriptedChatModel.constant("planner-model",
                ChatResponse.text("{\"assignments\":[{\"specialist\":\"ghost\",\"task\":\"x\"}]}"));
        SpecialistAgent researcher = SpecialistAgent.builder("researcher", "检索资料",
                        oneShot("检索结论"), new ToolRegistry())
                .keywords("资料")
                .build();

        SupervisorAgent supervisor = SupervisorAgent.builder("supervisor", oneShot("最终结论"))
                .specialist(researcher)
                .planner(new ModelPlanner(plannerModel, new KeywordPlanner()))
                .maxRounds(1)
                .build();

        SupervisorAgent.SupervisorResult result = supervisor.run("请查资料");

        // 模型的决定被丢弃后，关键词兜底仍然能完成委派
        assertTrue(result.delegatedSpecialists().contains("researcher"));
    }

    @Test
    @DisplayName("汇总失败时降级为拼接专家结论，不丢结果")
    void synthesisDegradesToConcatenation() {
        ScriptedChatModel brokenSupervisor = new ScriptedChatModel("broken", List.of(
                request -> {
                    throw new IllegalStateException("汇总模型挂了");
                }));
        SpecialistAgent researcher = SpecialistAgent.builder("researcher", "检索资料",
                        oneShot("检索结论"), new ToolRegistry())
                .keywords("资料")
                .build();

        SupervisorAgent supervisor = SupervisorAgent.builder("supervisor", brokenSupervisor)
                .specialist(researcher)
                .planner(new KeywordPlanner())
                .maxRounds(1)
                .build();

        SupervisorAgent.SupervisorResult result = supervisor.run("请查资料");

        assertTrue(result.synthesis().contains("检索结论"));
    }

    @Test
    @DisplayName("专家独立持有自己的 AgentLoop 与会话状态")
    void specialistHasOwnLoop() {
        SpecialistAgent researcher = SpecialistAgent.builder("researcher", "检索资料",
                        oneShot("检索结论"), new ToolRegistry())
                .build();

        AgentResult result = researcher.run("查资料");

        assertEquals(1, result.stepCount());
        // 专家自己的会话里只有：用户任务 + 专家回答（系统提示单独存放，不计入消息列表）
        assertEquals(2, researcher.state().messages().size());
        assertEquals(ChatMessage.Role.USER, researcher.state().messages().get(0).role());
        assertEquals(ChatMessage.Role.ASSISTANT, researcher.state().messages().get(1).role());
        assertTrue(researcher.loop() != null);
    }
}
