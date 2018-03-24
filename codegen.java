import java.util.*;

class CompilersCW {
    public static Codegen create() throws CodegenException {

        return new Codegen() {
            @Override
            public String codegen(Program p) throws CodegenException {
                Globals.i = 0;
                List<String> exps = new ArrayList<>();
                exps.addAll(genDecl(p.decls.get(0), true));
                exps.add("j exit");
                if (p.decls.size() > 1) {
                    for (int i = 1; i < p.decls.size(); i++) {
                        exps.addAll(genDecl(p.decls.get(i)));
                    }
                }
                exps.addAll(Arrays.asList(
                        "exit:",
                        ""
                ));
                String output = "";
                for (String instruction : exps) {
                    output += instruction + System.lineSeparator();
                }
                return output;
            }

            private List<String> genDecl(Declaration d, Boolean main) {
                List<String> exps = new ArrayList<>();

                int size = (2 + d.numOfArgs) * 4;
                exps.add(String.format("%s_entry:", d.id));
                exps.add("move $fp $sp");
                exps.addAll(push("$ra"));
                exps.addAll(genExp(d.body));
                exps.addAll(pop("$ra", size));
                exps.add("lw $fp 0($sp)");
                if (!main) {
                    exps.add("jr $ra");
                }
                return exps;
            }

            private List<String> genDecl(Declaration d) {
                return genDecl(d, false);
            }

            private List<String> genExp(Exp e) {
                List<String> exps = new ArrayList<>();
                switch (e.getClass().getSimpleName()) {
                    case "IntLiteral":
                        IntLiteral intLiteral = (IntLiteral) e;
                        exps.add(String.format("li $a0 %d", intLiteral.n));
                        break;
                    case "Variable":
                        Variable variable = (Variable) e;
                        exps.add(String.format("lw $a0 %d($fp)", variable.x * 4));
                        break;
                    case "If":
                        String thenLabel = getLabel("then");
                        String exitIfLabel = getLabel("exitIf", false);
                        If ifExp = (If) e;
                        exps.addAll(flattenList(Arrays.asList(
                                genComp(ifExp.l, ifExp.comp.getClass().getSimpleName(), ifExp.r, thenLabel),
                                Collections.singletonList(
                                        String.format("%s:", getLabel("else", false))
                                ),
                                genExp(ifExp.elseBody),
                                Arrays.asList(
                                        String.format("b %s", exitIfLabel),
                                        String.format("%s:", thenLabel)
                                ),
                                genExp(ifExp.thenBody),
                                Collections.singletonList(
                                        String.format("%s:", exitIfLabel)
                                )
                        )));
                        break;
                    case "Invoke":
                        Invoke call = (Invoke) e;
                        exps.addAll(push("$fp"));
                        for (int i = call.args.size() - 1; i >= 0; i--) {
                            exps.addAll(genExp(call.args.get(i)));
                            exps.addAll(push());
                        }
                        exps.add(String.format("jal %s_entry", call.name));
                        break;
                    case "Binexp":
                        Binexp binexp = (Binexp) e;
                        exps.addAll(genOp(binexp.l, binexp.r));
                        exps.add(String.format("%s $a0 $t1 $a0", new HashMap<String, String>() {{
                            put("Plus", "add");
                            put("Minus", "sub");
                            put("Times", "mul");
                            put("Div", "div");
                        }}.get(binexp.binop.getClass().getSimpleName())));
                        break;
                    case "While":
                        While w = (While) e;
                        exps.addAll(genLoop(w.l, w.comp, w.r, w.body, true));
                        break;
                    case "RepeatUntil":
                        RepeatUntil r = (RepeatUntil) e;
                        exps.addAll(genLoop(r.l, r.comp, r.r, r.body, false));
                        break;
                    case "Assign":
                        Assign assign = (Assign) e;
                        exps.addAll(genExp(assign.e));
                        exps.add(String.format("sw $a0 %d($fp)", assign.x * 4));
                        break;
                    case "Seq":
                        Seq seq = (Seq) e;
                        exps.addAll(flattenList(Arrays.asList(
                                genExp(seq.l),
                                genExp(seq.r)
                        )));
                        break;
                    case "Break":
                        exps.add(String.format("j loopEnd%d", Globals.loopId));
                        break;
                    case "Continue":
                        exps.add(String.format("j loop%s%d",
                                Globals.controlType.equals("while") ? "Start" : "Cond",
                                Globals.loopId
                        ));
                        break;
                }
                return exps;
            }

            private List<String> genLoop(Exp l, Comp comp, Exp r, Exp body, boolean whileLoop) {
                List<String> exps = new ArrayList<>();
                String startLabel = getLabel("loopStart");
                String condLabel = getLabel("loopCond", false);
                String endLabel = getLabel("loopEnd", false);
                Globals.loopId = Globals.i;
                Globals.controlType = whileLoop ? "while" : "repeat";
                if (whileLoop) {
                    exps.add(String.format("j %s", condLabel));
                }
                exps.addAll(flattenList(Arrays.asList(
                        Collections.singletonList(
                                String.format("%s:", startLabel)
                        ),
                        genExp(body),
                        Collections.singletonList(
                                String.format("%s:", condLabel)
                        ),
                        genOp(l, r),
                        genComp(l, comp.getClass().getSimpleName(), r, startLabel, !whileLoop),
                        Collections.singletonList(
                                String.format("%s:", endLabel)
                        )
                )));
                return exps;
            }

            private List<String> genOp(Exp l, Exp r) {
                return new ArrayList<>(flattenList(Arrays.asList(
                        genExp(l),
                        push(),
                        genExp(r),
                        pop("$t1")
                )));
            }

            private String invertComp(String comp) {
                return new HashMap<String, String>() {{
                    put("Greater", "LessEq");
                    put("GreaterEq", "Less");
                    put("Less", "GreaterEq");
                    put("LessEq", "Greater");
                    put("Equals", "NEquals");
                    put("NEquals", "Equals");
                }}.get(comp);
            }

            private List<String> genComp(Exp l, String comp, Exp r, String label, boolean inv) {
                List<String> exps = new ArrayList<>();
                exps.addAll(genOp(l, r));
                if(inv) {
                    comp = invertComp(comp);
                }
                switch (comp) {
                    case "Equals":
                        exps.add(String.format("beq $a0 $t1 %s", label));
                        break;
                    case "NEquals":
                        exps.add(String.format("bne $a0 $t1 %s", label));
                        break;
                    case "Greater":
                        exps.addAll(Arrays.asList(
                                "sub $a0 $t1 $a0",
                                String.format("bgtz $a0 %s", label)
                        ));
                        break;
                    case "GreaterEq":
                        exps.addAll(Arrays.asList(
                                "sub $a0 $t1 $a0",
                                String.format("bgez $a0 %s", label)
                        ));
                        break;
                    case "Less":
                        exps.addAll(Arrays.asList(
                                "sub $a0 $a0 $t1",
                                String.format("bltz $a0 %s", label)
                        ));
                        break;
                    case "LessEq":
                        exps.addAll(Arrays.asList(
                                "sub $a0 $t1 $a0",
                                String.format("blez $a0 %s", label)
                        ));
                        break;
                }
                return exps;
            }

            private List<String> genComp(Exp l, String comp, Exp r, String label) {
                return genComp(l, comp, r, label, false);
            }


            private List<String> push(String addr) {
                return Arrays.asList(
                        String.format("sw %s 0($sp)", addr),
                        "addiu $sp $sp -4"
                );
            }

            private List<String> push() {
                return push("$a0");
            }

            private List<String> pop(String addr, int size) {
                return Arrays.asList(
                        String.format("lw %s 4($sp)", addr),
                        String.format("addiu $sp $sp %d", size)
                );
            }

            private List<String> pop(String addr) {
                return pop(addr, 4);
            }

            private List<String> pop() {
                return pop("$a0");
            }

            private List<String> flattenList(List<List<String>> lists) {
                List<String> s = new ArrayList<>();
                for (List<String> list : lists) {
                    s.addAll(list);
                }
                return s;
            }

            private String getLabel(String prefix) {
                return getLabel(prefix, true);

            }

            private String getLabel(String prefix, boolean incr) {
                if (incr) {
                    Globals.i++;
                }
                return String.format("%s%d", prefix, Globals.i);
            }
        };
    }
}


class Globals {
    public static int i;
    public static int loopId;
    public static String controlType;
}
