package br.univali.portugol;

import br.univali.portugol.nucleo.ErroCompilacao;
import br.univali.portugol.nucleo.Portugol;
import br.univali.portugol.nucleo.analise.ResultadoAnalise;
import br.univali.portugol.nucleo.asa.TipoDado;
import br.univali.portugol.nucleo.bibliotecas.base.GerenciadorBibliotecas;
import br.univali.portugol.nucleo.bibliotecas.base.MetaDadosBiblioteca;
import br.univali.portugol.nucleo.bibliotecas.base.MetaDadosConstante;
import br.univali.portugol.nucleo.bibliotecas.base.MetaDadosFuncao;
import br.univali.portugol.nucleo.bibliotecas.base.MetaDadosParametro;
import br.univali.portugol.nucleo.mensagens.AvisoAnalise;
import br.univali.portugol.nucleo.mensagens.ErroAnalise;
import br.univali.portugol.nucleo.programa.Programa;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.LogManager;

/**
 * Ferramenta de linha de comando que roda <b>apenas a análise</b> de um programa em
 * Portugol e imprime os erros e avisos encontrados.
 * <p>
 * Ao contrário do {@link Console}, esta ferramenta não compila para Java nem executa o
 * programa: ela chama {@link Portugol#compilarParaAnalise(String)}, o que dispensa o
 * {@code javac} e permite analisar centenas de programas em segundos.
 * <p>
 * Ela existe para servir de referência executável a outras implementações do Portugol —
 * o Portugol Webstudio usa a saída daqui para comparar, programa a programa, os
 * diagnósticos do analisador dele com os do Portugol Studio.
 *
 * <h2>Uso</h2>
 * <pre>
 * java -jar portugol-analisador.jar programa.por [outro.por ...]
 * java -jar portugol-analisador.jar -            # lê o programa da entrada padrão
 * java -jar portugol-analisador.jar --bibliotecas
 * </pre>
 *
 * <h2>Formato da saída</h2>
 * Uma linha {@code ### <arquivo>} por programa, seguida de uma linha por diagnóstico:
 * <pre>
 * TIPO|LINHA|COLUNA|CODIGO|MENSAGEM
 * </pre>
 * onde {@code TIPO} é {@code ERRO}, {@code AVISO} ou {@code FATAL}. O separador
 * {@code |} é trocado por {@code /} dentro da mensagem, e quebras de linha viram
 * espaços, de modo que cada diagnóstico ocupe exatamente uma linha.
 * <p>
 * Com {@code --bibliotecas}, imprime os metadados de todas as bibliotecas em JSON.
 */
public final class Analisador
{
    private static final String ENTRADA_PADRAO = "-";

    private static PrintStream saida;

    public static void main(String[] args) throws Exception
    {
        // Sem isto o núcleo escreve log na saída padrão e polui o formato.
        LogManager.getLogManager().reset();

        saida = new PrintStream(System.out, true, "UTF-8");

        List<String> parametros = new ArrayList<>();
        boolean bibliotecas = false;

        for (String arg : args)
        {
            if ("--bibliotecas".equals(arg))
            {
                bibliotecas = true;
            }
            else if ("--ajuda".equals(arg) || "--help".equals(arg) || "-h".equals(arg))
            {
                exibirAjuda();
                return;
            }
            else
            {
                parametros.add(arg);
            }
        }

        if (bibliotecas)
        {
            exibirBibliotecas();
        }
        else if (parametros.isEmpty())
        {
            exibirAjuda();
            System.exit(1);
        }
        else
        {
            for (String caminho : parametros)
            {
                analisar(caminho);
            }
        }

        saida.flush();

        // O núcleo deixa threads não-daemon vivas (o pool de execução das bibliotecas),
        // então é preciso encerrar explicitamente.
        System.exit(0);
    }

    private static void exibirAjuda()
    {
        saida.println("Analisa programas em Portugol e imprime os erros e avisos encontrados,");
        saida.println("sem compilar para Java nem executar o programa.");
        saida.println();
        saida.println("Uso:");
        saida.println("  portugol-analisador <programa.por> [outro.por ...]");
        saida.println("  portugol-analisador -              lê o programa da entrada padrão");
        saida.println("  portugol-analisador --bibliotecas  imprime os metadados das bibliotecas em JSON");
        saida.println();
        saida.println("Saída: uma linha '### <arquivo>' por programa, seguida de uma linha por");
        saida.println("diagnóstico no formato TIPO|LINHA|COLUNA|CODIGO|MENSAGEM.");
    }

    private static void analisar(String caminho) throws IOException
    {
        saida.println("### " + caminho);

        String codigo;

        try
        {
            codigo = ENTRADA_PADRAO.equals(caminho) ? ler(System.in) : lerArquivo(new File(caminho));
        }
        catch (IOException excecao)
        {
            saida.println("FATAL|0|0||" + limpar("Não foi possível ler o arquivo: " + excecao.getMessage()));

            return;
        }

        try
        {
            Programa programa = Portugol.compilarParaAnalise(codigo);

            if (programa != null && programa.getResultadoAnalise() != null)
            {
                exibirResultado(programa.getResultadoAnalise());
            }
        }
        catch (ErroCompilacao erroCompilacao)
        {
            exibirResultado(erroCompilacao.getResultadoAnalise());
        }
        catch (Throwable excecao)
        {
            // O analisador do núcleo estoura exceção não tratada em alguns programas
            // (ClassCastException quando uma função tem o mesmo nome de uma variável
            // global, por exemplo). Reportar como FATAL é mais útil do que abortar o
            // lote inteiro.
            saida.println("FATAL|0|0||" + limpar(String.valueOf(excecao)));
        }
    }

    private static void exibirResultado(ResultadoAnalise resultado)
    {
        for (ErroAnalise erro : resultado.getErros())
        {
            saida.println("ERRO|" + erro.getLinha() + "|" + erro.getColuna() + "|"
                    + limpar(erro.getCodigo()) + "|" + limpar(erro.getMensagem()));
        }

        for (AvisoAnalise aviso : resultado.getAvisos())
        {
            saida.println("AVISO|" + aviso.getLinha() + "|" + aviso.getColuna() + "|"
                    + limpar(aviso.getCodigo()) + "|" + limpar(aviso.getMensagem()));
        }
    }

    private static void exibirBibliotecas()
    {
        GerenciadorBibliotecas gerenciador = GerenciadorBibliotecas.getInstance();

        saida.println("{");

        boolean primeiraBiblioteca = true;

        for (String nome : gerenciador.listarBibliotecasDisponiveis())
        {
            MetaDadosBiblioteca biblioteca;

            try
            {
                biblioteca = gerenciador.obterMetaDadosBiblioteca(nome);
            }
            catch (Throwable excecao)
            {
                continue;
            }

            if (!primeiraBiblioteca)
            {
                saida.println(",");
            }

            primeiraBiblioteca = false;

            saida.println("  " + json(nome) + ": {");
            saida.println("    \"tipo\": " + json(String.valueOf(biblioteca.getTipo())) + ",");

            exibirConstantes(biblioteca);
            exibirFuncoes(biblioteca);

            saida.print("  }");
        }

        saida.println();
        saida.println("}");
    }

    private static void exibirConstantes(MetaDadosBiblioteca biblioteca)
    {
        saida.println("    \"constantes\": {");

        boolean primeira = true;

        for (MetaDadosConstante constante : biblioteca.getMetaDadosConstantes())
        {
            if (!primeira)
            {
                saida.println(",");
            }

            primeira = false;

            saida.print("      " + json(constante.getNome()) + ": { "
                    + "\"tipo\": " + json(nomeDoTipo(constante.getTipoDado())) + ", "
                    + "\"quantificador\": " + json(String.valueOf(constante.getQuantificador())) + ", "
                    + "\"valor\": " + json(String.valueOf(constante.getValor())) + " }");
        }

        saida.println();
        saida.println("    },");
    }

    private static void exibirFuncoes(MetaDadosBiblioteca biblioteca)
    {
        saida.println("    \"funcoes\": {");

        boolean primeira = true;

        for (MetaDadosFuncao funcao : biblioteca.obterMetaDadosFuncoes())
        {
            if (!primeira)
            {
                saida.println(",");
            }

            primeira = false;

            saida.println("      " + json(funcao.getNome()) + ": {");
            saida.println("        \"tipo\": " + json(nomeDoTipo(funcao.getTipoDado())) + ",");
            saida.println("        \"quantificador\": " + json(String.valueOf(funcao.getQuantificador())) + ",");
            saida.print("        \"parametros\": [");

            boolean primeiroParametro = true;

            for (MetaDadosParametro parametro : funcao.obterMetaDadosParametros())
            {
                if (!primeiroParametro)
                {
                    saida.print(", ");
                }

                primeiroParametro = false;

                saida.print("{ \"nome\": " + json(parametro.getNome())
                        + ", \"tipo\": " + json(nomeDoTipo(parametro.getTipoDado()))
                        + ", \"modoAcesso\": " + json(String.valueOf(parametro.getModoAcesso()))
                        + ", \"quantificador\": " + json(String.valueOf(parametro.getQuantificador())) + " }");
            }

            saida.println("]");
            saida.print("      }");
        }

        saida.println();
        saida.println("    }");
    }

    /**
     * O nome do tipo como o Portugol o escreve — {@code TipoDado.getNome()} devolve
     * "logico" sem acento, que é a palavra-chave da linguagem.
     */
    private static String nomeDoTipo(TipoDado tipoDado)
    {
        return tipoDado == null ? "" : tipoDado.getNome();
    }

    /**
     * Deixa o texto seguro para o formato de uma linha: sem quebras, sem tabulação e sem
     * o separador de campos.
     */
    private static String limpar(String texto)
    {
        if (texto == null)
        {
            return "";
        }

        return texto.replace("\r", " ").replace("\n", " ").replace("\t", " ").replace("|", "/");
    }

    private static String json(String texto)
    {
        if (texto == null)
        {
            return "null";
        }

        StringBuilder construtor = new StringBuilder("\"");

        for (char caracter : texto.toCharArray())
        {
            switch (caracter)
            {
                case '"':
                    construtor.append("\\\"");
                    break;
                case '\\':
                    construtor.append("\\\\");
                    break;
                case '\n':
                    construtor.append("\\n");
                    break;
                case '\r':
                    construtor.append("\\r");
                    break;
                case '\t':
                    construtor.append("\\t");
                    break;
                default:
                    if (caracter < 0x20)
                    {
                        construtor.append(String.format("\\u%04x", (int) caracter));
                    }
                    else
                    {
                        construtor.append(caracter);
                    }
            }
        }

        return construtor.append('"').toString();
    }

    private static String lerArquivo(File arquivo) throws IOException
    {
        InputStream entrada = new FileInputStream(arquivo);

        try
        {
            return ler(entrada);
        }
        finally
        {
            entrada.close();
        }
    }

    private static String ler(InputStream entrada) throws UnsupportedEncodingException, IOException
    {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] pedaco = new byte[8192];
        int lidos;

        while ((lidos = entrada.read(pedaco)) != -1)
        {
            buffer.write(pedaco, 0, lidos);
        }

        return new String(buffer.toByteArray(), "UTF-8");
    }

    private Analisador()
    {
    }
}
